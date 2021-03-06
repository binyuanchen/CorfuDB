package org.corfudb.runtime.smr;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.thrift.Hints;
import org.corfudb.runtime.entries.IStreamEntry;
import org.corfudb.runtime.smr.smrprotocol.LambdaSMRCommand;
import org.corfudb.runtime.smr.smrprotocol.TransactionalLambdaSMRCommand;
import org.corfudb.runtime.stream.IStream;
import org.corfudb.runtime.stream.ITimestamp;
import org.corfudb.runtime.stream.SimpleTimestamp;
import org.corfudb.runtime.view.ICorfuDBInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A deferred transaction gets resolved at runtime:
 * Deferred transactions are always successful if they are successfully proposed to the
 * log. They are only executed on the upcall, and as a result, it is not possible
 * a priori to know which streams the deferred transaction will be a part of.
 *
 * This means that while deferred transactions are guaranteed to execute, every single
 * stream must execute the deferred transaction to determine whether the transaction
 * will affect it.
 *
 * Created by mwei on 5/3/15.
 */
@Slf4j
public class DeferredTransaction<R> implements ITransaction<R>, Serializable {

    @Getter
    @Setter
    ITransactionCommand<R> transaction;

    @Getter
    @Setter
    public List<UUID> streamList;

    @Getter
    @Setter
    public transient ICorfuDBInstance instance;

    @Setter
    public transient ITimestamp timestamp;

    public DeferredTransaction(ICorfuDBInstance instance)
    {
        streamList = new ArrayList<>();
        this.instance = instance;
    }

    public DeferredTransaction(ICorfuDBInstance instance, ITransactionCommand<R> transaction)
    {
        this(instance);
        this.transaction = transaction;
    }

    /**
     * Returns an SMR engine for a transactional context.
     *
     * @param streamID The streamID the SMR engine should run on.
     * @param objClass The class that the SMR engine runs against.
     * @return The SMR engine to be used for a transactional context.
     */
    @Override
    @SuppressWarnings("unchecked")
    public ISMREngine getEngine(UUID streamID, Class<?> objClass) {
        /* do we have this object in memory? */
        ICorfuDBObject obj = instance.openObject(streamID, (Class<? extends ICorfuDBObject>) objClass);
        // lock the object, add it to the list of objects we own

        // now check if the object is at the version we need (or before, in case we can sync).
        if (obj.getUnderlyingSMREngine().getStreamPointer().compareTo(timestamp) > 0)
        {
            throw new UnsupportedOperationException("Not yet implemented: object out of sync. " +
                    "ts=" + obj.getUnderlyingSMREngine().getStreamPointer() + ", " +
                    "need ts="  + timestamp + "-1");
        }

        // return a passthrough engine, which runs the command directly on the object.
        // we cannot release the object until the TX is done executing.
        if (obj != null) {
            return new PassThroughSMREngine<>(obj.getUnderlyingSMREngine().getObject(),
                    timestamp,
                    instance,
                    streamID
            );
        }
        else {
            throw new UnsupportedOperationException("Can't open remote objects, yet!");
        }
        /*
        if (streamID.equals(executingEngine.getStreamID()))
        {
            PassThroughSMREngine engine = new PassThroughSMREngine(executingEngine.getObject(), timestamp, instance, streamID);
            passThroughEngines.add(engine);
            return engine;
        }
        else
        {
            IBufferedSMREngine engine = bufferedSMRMap.get(streamID);
            // TODO: Do we even need to consider other engines??
            if (engine == null && executingEngine instanceof SimpleSMREngine) {
                engine = (IBufferedSMREngine) ((SimpleSMREngine) executingEngine).getCachedEngines().get(streamID);
                if (engine == null) {
                    IStream sTemp = instance.openStream(streamID, EnumSet.of(ICorfuDBInstance.OpenStreamFlags.NON_CACHED));
                    engine = new CachedSMREngine(sTemp, objClass, timestamp);
                    engine.sync(timestamp);
                    ((SimpleSMREngine) executingEngine).addCachedEngine(streamID, engine);
                }
                bufferedSMRMap.put(streamID, engine);
            }
            return engine;
        }
        */
    }

    /**
     * Execute this command on a specific SMR engine.
     *
     * @param engine The SMR engine to run this command on.
     */
    @Override
    public R executeTransaction(ISMREngine engine) {
        log.info("Executing transaction.");
        try (TransactionalContext tc = new TransactionalContext(this)) {
            return transaction.apply(engine.getObject());
        }
    }

    /**
     * Propose to the SMR engine(s) for the transaction to be executed.
     *
     * @return The timestamp that the transaction was proposed at.
     * This timestamp should be a valid timestamp for all streams
     * that the transaction belongs to, otherwise, the transaction
     * will abort.
     */
    @Override
    public ITimestamp propose()
    throws IOException
    {
        try {
            Long sequence = instance.getNewStreamingSequencer().nextTokenAsync(Collections.<UUID>emptySet(), 1)
                    .thenApplyAsync(x -> {
                        TransactionalContext.setTransactionalFuture(
                                new SimpleTimestamp(x), new CompletableFuture());
                        instance.getStreamAddressSpace().write(x, Collections.<UUID>emptySet(),
                                new TransactionalLambdaSMRCommand<>(this));
                        return x;
                    }).get();
            return new SimpleTimestamp(sequence);
        } catch (Exception e)
        {
            log.error("Error during tx propose", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<R> executeAsync() {
        NullSMREngine nse = new NullSMREngine(instance);
        ITimestamp t;
        try {
            t = propose();
            nse.sync(t);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        return (CompletableFuture<R>) TransactionalContext.getTransactionalFuture(t);
    }
}
