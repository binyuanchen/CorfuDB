package org.corfudb.infrastructure;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.corfudb.infrastructure.thrift.ErrorCode;
import org.corfudb.infrastructure.thrift.ReadCode;
import org.corfudb.runtime.OverwriteException;
import org.corfudb.runtime.protocols.logunits.CorfuNewLogUnitProtocol;
import org.corfudb.runtime.protocols.logunits.INewWriteOnceLogUnit;
import org.corfudb.util.CorfuInfrastructureBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;

import static com.github.marschall.junitlambda.LambdaAssert.assertRaises;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by mwei on 8/26/15.
 */
@Slf4j
public class NewLogUnitServerIT {

    CorfuNewLogUnitProtocol nlup;
    CorfuInfrastructureBuilder infrastructure;

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            System.out.println("Starting test: " + description.getMethodName());
        }
    };

    @Before
    public void setup()
    throws Exception
    {
        infrastructure =
                CorfuInfrastructureBuilder.getBuilder()
                        .addSequencer(9873, StreamingSequencerServer.class, "cdbss", null)
                        .addLoggingUnit(7777, 0, NewLogUnitServer.class, "cnlu", null)
                        .start(7775);
        nlup = new CorfuNewLogUnitProtocol("localhost", 7777, Collections.emptyMap(), 0L);
    }

    @After
    public void tearDown()
    throws Exception
    {
        infrastructure.shutdownAndWait();
    }

    private static byte[] getTestPayload(int size)
    {
        byte[] test = new byte[size];
        for (int i = 0; i < size; i++)
        {
            test[i] = (byte)(i % 255);
        }
        return test;
    }

    @Test
    public void canPing()
        throws Exception
    {
        nlup.reset(0);
        assertThat(nlup.ping())
                .isEqualTo(true);
    }

    @Test
    public void canReadWrites()
            throws Exception
    {
        nlup.reset(0);
        byte[] testPayload = getTestPayload(1024);
        nlup.write(0, Collections.emptySet(), ByteBuffer.wrap(testPayload));
        assertThat(nlup.read(0).getDataAsArray())
                .isEqualTo(testPayload);
    }

    @Test
    public void overwriteReturnsOverwrite()
        throws Exception
    {
        nlup.reset(0);
        byte[] testPayload = getTestPayload(1024);
        nlup.write(0, Collections.emptySet(), ByteBuffer.wrap(testPayload));
        assertRaises(() -> nlup.write(0, Collections.emptySet(), ByteBuffer.wrap(testPayload)), OverwriteException.class);
    }

    @Test
    public void streamIDsAreStored()
        throws Exception
    {
        nlup.reset(0);
        byte[] testPayload = getTestPayload(1024);
        UUID id = UUID.randomUUID();
        nlup.write(0, new HashSet<UUID>(Collections.singletonList(id)), ByteBuffer.wrap(testPayload));
        val read = nlup.read(0);
        assertThat(read.getStreams().contains(id))
                .isEqualTo(true);
    }

    @Test
    public void holesAreReportedAsHoles()
            throws Exception
    {
        nlup.reset(0);
        // Hole filling is async/unreliable, so we wait a reasonable amount of time
        nlup.fillHole(0);
        Thread.sleep(200);
        assertThat(nlup.read(0).getResult())
                .isEqualTo(ReadCode.READ_FILLEDHOLE);
    }

    @Test
    public void holesCannotBeOverwritten()
        throws Exception
    {
        nlup.reset(0);
        nlup.fillHole(0);
        Thread.sleep(200);
        byte[] testPayload = getTestPayload(1024);
        assertRaises(() -> nlup.write(0, Collections.emptySet(), ByteBuffer.wrap(testPayload)), OverwriteException.class);
        assertThat(nlup.read(0).getResult())
                .isEqualTo(ReadCode.READ_FILLEDHOLE);
    }
}