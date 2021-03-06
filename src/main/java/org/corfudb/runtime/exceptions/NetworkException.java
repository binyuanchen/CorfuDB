/**
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.corfudb.runtime.exceptions;
import java.io.IOException;
import org.corfudb.runtime.protocols.IServerProtocol;
/**
 * This exception is thrown whenever the result of an operation
 * is unknown due to a network error
 */
@SuppressWarnings("serial")
public class NetworkException extends IOException
{
    public IServerProtocol protocol;
    public long address;
    public boolean write;

    public NetworkException(String desc, IServerProtocol protocol)
    {
        super(desc + "[server=" + protocol.getFullString() + "]");
        this.protocol = protocol;
    }

    public NetworkException(String desc, IServerProtocol protocol, long address, boolean write)
    {
        super(desc + "[server=" + protocol.getFullString() + ", address= " + address + ", r/w=" + (write ? "W" : "R") + "]");
        this.protocol = protocol;
        this.address = address;
        this.write = write;
    }
}

