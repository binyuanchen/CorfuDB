package org.corfudb.runtime.smr;

import org.corfudb.runtime.CorfuDBRuntime;
import org.corfudb.runtime.entries.IStreamEntry;
import org.corfudb.runtime.stream.IStream;
import org.corfudb.runtime.stream.ITimestamp;
import org.corfudb.runtime.stream.SimpleStream;
import org.corfudb.runtime.stream.SimpleTimestamp;
import org.corfudb.runtime.view.ISequencer;
import org.corfudb.runtime.view.IWriteOnceAddressSpace;
import org.corfudb.runtime.view.StreamingSequencer;
import org.corfudb.runtime.view.WriteOnceAddressSpace;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Created by mwei on 5/3/15.
 */
public class SimpleTransaction implements ITransaction, IStreamEntry, Serializable {

    ITransactionCommand transaction;
    List<UUID> streamList;
    ITimestamp timestamp;
    transient CorfuDBRuntime runtime;
    transient ISMREngine executingEngine;

    class SimpleTransactionOptions implements ITransactionOptions
    {

        public SimpleTransactionOptions() {
        }

        @Override
        public CompletableFuture<Object> getReturnResult() {
            return null;
        }
    }

    public SimpleTransaction(CorfuDBRuntime runtime)
    {
        streamList = new ArrayList<UUID>();
        this.runtime = runtime;
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
        if (streamID.equals(executingEngine.getStreamID()))
        {
            return new PassThroughSMREngine(executingEngine.getObject(), timestamp);
        }
        else
        {
            IWriteOnceAddressSpace woas = new WriteOnceAddressSpace(runtime);
            StreamingSequencer ss = new StreamingSequencer(runtime);
            IStream sTemp = new SimpleStream(streamID, ss, woas);
            ISMREngine engine = new OneShotSMREngine(sTemp, objClass, timestamp);
            engine.sync(timestamp);
            return engine;
        }
    }

    /**
     * Registers a stream to be part of a transactional context.
     *
     * @param stream A stream that will be joined into this transaction.
     */
    @Override
    public void registerStream(UUID stream) {
        streamList.add(stream);
    }

    /**
     * Sets the CorfuDB runtime for this transaction. Used when deserializing
     * the transaction.
     *
     * @param runtime The runtime to use for this transaction.
     */
    @Override
    public void setCorfuDBRuntime(CorfuDBRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Set the command to be executed for this transaction.
     *
     * @param transaction The command(s) to be executed for this transaction.
     */
    @Override
    public void setTransaction(ITransactionCommand transaction) {
        this.transaction = transaction;
    }

    /**
     * Execute this command on a specific SMR engine.
     *
     * @param engine The SMR engine to run this command on.
     */
    @Override
    public void executeTransaction(ISMREngine engine) {
        ITransactionCommand command = getTransaction();
        executingEngine = engine;
        if (!command.apply(new SimpleTransactionOptions()))
        {
            throw new RuntimeException("Transaction was aborted but SimpleTX does not support aborted TX!");
        }
    }

    /**
     * Returns the transaction command.
     *
     * @return The command(s) to be executed for this transaction.
     */
    @Override
    public ITransactionCommand getTransaction() {
        return this.transaction;
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
        /* The simple transaction just assumes that everything is on the same log,
         * so picking the next valid sequence is acceptable.
         */
        ISequencer sequencer = new StreamingSequencer(runtime);
        IWriteOnceAddressSpace woas = new WriteOnceAddressSpace(runtime);
        Long sequence = sequencer.getNext();
        woas.write(sequence, this);
        return new SimpleTimestamp(sequence);
    }

    /**
     * Gets the list of of the streams this entry belongs to.
     *
     * @return The list of streams this entry belongs to.
     */
    @Override
    public List<UUID> getStreamIds() {
        return streamList;
    }

    /**
     * Returns whether this entry belongs to a given stream ID.
     *
     * @param stream The stream ID to check
     * @return True, if this entry belongs to that stream, false otherwise.
     */
    @Override
    public boolean containsStream(UUID stream) {
        return streamList.contains(stream);
    }

    /**
     * Gets the timestamp of the stream this entry belongs to.
     *
     * @return The timestamp of the stream this entry belongs to.
     */
    @Override
    public ITimestamp getTimestamp() {
        return timestamp;
    }

    /**
     * Set the timestamp.
     *
     * @param ts    The new timestamp of the entry.
     */
    @Override
    public void setTimestamp(ITimestamp ts) {
        timestamp = ts;
    }

    /**
     * Gets the payload of this stream.
     *
     * @return The payload of the stream.
     */
    @Override
    public Object getPayload() {
        return this;
    }
}