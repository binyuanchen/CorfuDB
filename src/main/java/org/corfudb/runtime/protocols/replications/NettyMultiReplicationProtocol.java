package org.corfudb.runtime.protocols.replications;

import lombok.SneakyThrows;
import org.corfudb.infrastructure.NettyLogUnitServer;
import org.corfudb.infrastructure.thrift.ExtntWrap;
import org.corfudb.runtime.*;
import org.corfudb.runtime.protocols.IServerProtocol;
import org.corfudb.runtime.protocols.logunits.INewWriteOnceLogUnit;
import org.corfudb.runtime.protocols.logunits.IWriteOnceLogUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;

/**
 * TODO: ASSUMES ONLY 2 NODES IN THE CHAIN!!
 * The top layer in MultiIndexReplication must be IWriteOnceLogUnits.
 *
 * The bottom layer MUST BE INewWriteOnceLogUnits (In particular, probably use Netty).
 *
 * Created by taia on 9/2/15.
 */
public class NettyMultiReplicationProtocol implements IStreamAwareRepProtocol {
    private static final Logger log = LoggerFactory.getLogger(NettyMultiReplicationProtocol.class);
    private List<List<IServerProtocol>> layers = null;

    public NettyMultiReplicationProtocol(List<List<IServerProtocol>> layers) {
        this.layers = layers;
    }

    public static String getProtocolString()
    {
        return "cdbnmrp";
    }

    public static Map<String, Object> segmentParser(JsonObject jo) {
        Map<String, Object> ret = new HashMap<String, Object>();
        ArrayList<Map<String, Object>> groupList = new ArrayList<Map<String, Object>>();
        for (JsonValue j2 : jo.getJsonArray("layers"))
        {
            HashMap<String,Object> groupItem = new HashMap<String,Object>();
            JsonArray ja = (JsonArray) j2;
            ArrayList<String> group = new ArrayList<String>();
            for (JsonValue j3 : ja)
            {
                group.add(((JsonString)j3).getString());
            }
            groupItem.put("nodes", group);
            groupList.add(groupItem);
        }
        ret.put("layers", groupList);

        return ret;
    }

    public static IStreamAwareRepProtocol initProtocol(Map<String, Object> fields,
                                                       Map<String,Class<? extends IServerProtocol>> availableLogUnitProtocols,
                                                       Long epoch) {
        return new NettyMultiReplicationProtocol(populateLayersFromList((List<Map<String, Object>>) fields.get("layers"), availableLogUnitProtocols, epoch));
    }

    @Override
    public List<List<IServerProtocol>> getGroups() {
        return layers;
    }


    @Override
    @SneakyThrows
    public void write(CorfuDBRuntime client, long address, Map<UUID, Long> streams, byte[] data)
            throws OverwriteException, TrimmedException, OutOfSpaceException, SubLogException {
        // TODO: Handle multiple segments?
        IStreamAwareRepProtocol reconfiguredRP = null;

        while (true)
        {
            if (reconfiguredRP != null) {
                reconfiguredRP.write(client, address, streams, data);
                return;
            }
            long layer0 = address % layers.get(0).size();
            if (layer0 < 0)
                layer0 += layers.get(0).size();
            IServerProtocol first = layers.get(0).get((int)layer0);

            long layer1 = streams.keySet().iterator().next().hashCode() % layers.get(1).size();
            if (layer1 < 0)
                layer1 += layers.get(1).size();
            IServerProtocol second = layers.get(1).get((int)layer1);

            // TODO: Netty protocol needs to return a richer write for OverwriteExceptions
            CompletableFuture<INewWriteOnceLogUnit.WriteResult> asyncResult =
                    ((INewWriteOnceLogUnit) first).write(address, streams, 0, data).thenComposeAsync(
                            w -> {
                                if (w.equals(INewWriteOnceLogUnit.WriteResult.OVERWRITE) ||
                                        w.equals(INewWriteOnceLogUnit.WriteResult.SUB_LOG)) {
                                    CompletableFuture<INewWriteOnceLogUnit.WriteResult> ret =
                                            new CompletableFuture<INewWriteOnceLogUnit.WriteResult>();
                                    ret.complete(w);
                                    return ret;
                                }
                                else {
                                    return ((INewWriteOnceLogUnit) second).write(address, streams, 0, data).thenCompose(
                                            sw -> {
                                                if (sw.equals(INewWriteOnceLogUnit.WriteResult.OVERWRITE) ||
                                                        sw.equals(INewWriteOnceLogUnit.WriteResult.SUB_LOG)) {
                                                    CompletableFuture<INewWriteOnceLogUnit.WriteResult> ret =
                                                            new CompletableFuture<INewWriteOnceLogUnit.WriteResult>();
                                                    ret.complete(sw);
                                                    return ret;
                                                }
                                                else {
                                                    // Now we write the commit bits
                                                    return (((INewWriteOnceLogUnit) first).setCommit(address, true)).thenCompose(
                                                            cw -> (((INewWriteOnceLogUnit) second).setCommit(streams, true))
                                                    );
                                                }
                                            }
                                    );
                                }
                            }
                    );
            if (asyncResult == null) {
                log.warn("Netty returned null CompletableFuture..");
                client.invalidateViewAndWait(null);
                reconfiguredRP = client.getView().getSegments().get(0).getStreamAwareRepProtocol();
                continue;
            }

            switch (asyncResult.get()) {
                case OK:
                    return;
                case OOS:
                    throw new OutOfSpaceException("Out of Space, netty MIRP", address);
                case TRIMMED:
                    throw new TrimmedException("trimmed exception, netty MIRP", address);
                case OVERWRITE:
                    throw new OverwriteException("Overwrite Exception, netty MIRP, no data", address, null);
                case SUB_LOG:
                    throw new SubLogException("Sublog Netty exception trying to insert..", address, streams);
                default:
                    throw new RuntimeException("Unknown case in write for nett MIRP" + asyncResult);
            }
        }
    }

    @Override
    @SneakyThrows
    public byte[] read(CorfuDBRuntime client, long physAddress, UUID stream, long logAddress)
            throws UnwrittenException, TrimmedException {
        IStreamAwareRepProtocol reconfiguredRP = null;
        while (true)
        {
                if (reconfiguredRP != null) {
                    byte[] data = reconfiguredRP.read(client, physAddress, stream, logAddress);
                    return data;
                }
                // Depending on if -1L if in the physAddress or logAddress, we read from a different Layer.
                if (logAddress == -1L) {
                    long layer0 = physAddress % layers.get(0).size();
                    if (layer0 < 0)
                        layer0 += layers.get(0).size();
                    IServerProtocol first = layers.get(0).get((int)layer0);
                    // check commit bit first
                    INewWriteOnceLogUnit.ReadResult rr = ((INewWriteOnceLogUnit)first).read(physAddress).get();

                    // TODO: check for null
                    if ((boolean)rr.getMetadataMap().get(NettyLogUnitServer.LogUnitMetadataType.COMMIT))
                        return (byte[]) rr.getPayload();
                    else throw new UnwrittenException("Address unwritten", physAddress);
                }
                else {
                    assert(physAddress == -1L);
                    long layer1 = stream.hashCode() % layers.get(1).size();
                    if (layer1 < 0)
                        layer1 += layers.get(1).size();
                    IServerProtocol second = layers.get(1).get((int)layer1);

                    INewWriteOnceLogUnit.ReadResult rr = ((INewWriteOnceLogUnit)second).read(stream, logAddress).get();
                    // TODO: check for null
                    if ((boolean)rr.getMetadataMap().get(NettyLogUnitServer.LogUnitMetadataType.COMMIT))
                        return (byte[]) rr.getPayload();
                    else throw new UnwrittenException("Address unwritten", stream, logAddress);
                }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<List<IServerProtocol>> populateLayersFromList(List<Map<String, Object>> list,
                                                                      Map<String, Class<? extends IServerProtocol>> availableLogUnitProtocols,
                                                                      long epoch) {
        ArrayList<List<IServerProtocol>> layers = new ArrayList<List<IServerProtocol>>();
        for (Map<String,Object> map : list)
        {
            ArrayList<IServerProtocol> nodes = new ArrayList<IServerProtocol>();
            for (String node : (List<String>)map.get("nodes"))
            {
                String protocol = null;
                Matcher m = IServerProtocol.getMatchesFromServerString(node);
                if (m.find())
                {
                    if (protocol != null) {
                        if (protocol != m.group("protocol")) {
                            log.warn("Nodes in the same layer have different protocols");
                            return null;
                        }
                    }
                    protocol = m.group("protocol");
                    if (!availableLogUnitProtocols.keySet().contains(protocol))
                    {
                        log.warn("Unsupported logunit protocol: " + protocol);
                    }
                    else
                    {
                        Class<? extends IServerProtocol> sprotocol = availableLogUnitProtocols.get(protocol);
                        try
                        {
                            nodes.add(IServerProtocol.protocolFactory(sprotocol, node, epoch));
                        }
                        catch (Exception ex){
                            log.error("Error invoking protocol for protocol: ", ex);
                        }
                    }
                }
                else
                {
                    log.warn("Logunit string " + node + " appears to be an invalid logunit string");
                }
            }
            layers.add(nodes);
        }
        return layers;
    }
}
