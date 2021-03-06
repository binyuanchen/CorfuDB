package org.corfudb.util.serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.corfudb.runtime.smr.smrprotocol.SMRCommand;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by mwei on 9/29/15.
 */
public class CorfuSerializer implements ISerializer {

    //region Command Type
    @RequiredArgsConstructor
    public enum CorfuPayloadType {
        // Type of SMR command
        SMR(0, SMRCommand.class, SMRCommand::deserialize)
        ;

        final int type;
        @Getter
        final Class<? extends ICorfuSerializable> cls;
        final Function<ByteBuf, ?> deserializer;

        byte asByte() { return (byte)type; }
    };

    static Map<Byte, CorfuPayloadType> typeMap =
            Arrays.stream(CorfuPayloadType.values())
                    .collect(Collectors.toMap(CorfuPayloadType::asByte, Function.identity()));
    static Map<Class<?>, CorfuPayloadType> classMap =
            Arrays.stream(CorfuPayloadType.values())
                    .collect(Collectors.toMap(CorfuPayloadType::getCls, Function.identity()));
    //endregion

    //region Constants
        /* The magic that denotes this is a corfu payload */
        final byte CorfuPayloadMagic = 0x42;
    //endregion

    //region Serializer
    /**
     * Deserialize an object from a given byte buffer.
     *
     * @param b The bytebuf to deserialize.
     * @return The deserialized object.
     */
    @Override
    public Object deserialize(ByteBuf b) {
        byte magic;
        if ((magic = b.readByte()) != CorfuPayloadMagic) {
            b.resetReaderIndex();
            try (ByteBufInputStream bbis = new ByteBufInputStream(b))
            {
                try (ObjectInputStream ois = new ObjectInputStream(bbis))
                {
                    return ois.readObject();
                }
            }
            catch (IOException | ClassNotFoundException ie)
            {
                throw new RuntimeException(ie);
            }
        }
        CorfuPayloadType type = typeMap.get(b.readByte());
        if (type == null)
        {
            throw new ClassCastException("Unsupported/unknown payload type.");
        }
        return type.deserializer.apply(b);
    }

    /**
     * Serialize an object into a given byte buffer.
     *
     * @param o The object to serialize.
     * @param b The bytebuf to serialize it into.
     */
    @Override
    public void serialize(Object o, ByteBuf b) {
        if (o instanceof ICorfuSerializable)
        {
            b.writeByte(CorfuPayloadMagic);
            b.writeByte(0); //TODO: Support multiple types (this only assumes SMR).
            ICorfuSerializable c = (ICorfuSerializable) o;
            c.serialize(b);
        }
        else
        {
            //try to java serialize things.
            try (ByteBufOutputStream bbos = new ByteBufOutputStream(b))
            {
                try (ObjectOutputStream oos = new ObjectOutputStream(bbos))
                {
                    oos.writeObject(o);
                }
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }
    //endregion

}
