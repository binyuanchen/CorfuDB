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

package org.corfudb.runtime.protocols.sequencers;

import org.corfudb.runtime.protocols.IServerProtocol;
import org.corfudb.runtime.exceptions.NetworkException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.HashMap;
import java.util.Map;
public class RedisSequencerProtocol implements IServerProtocol, ISimpleSequencer
{
    private String host;
    private Integer port;
    private Map<String,String> options;

    private JedisPool pool;

    private Logger log = LoggerFactory.getLogger(RedisSequencerProtocol.class);

    public static String getProtocolString()
    {
        return "redisseq";
    }

    public Integer getPort()
    {
        return port;
    }

    public String getHost()
    {
        return host;
    }

    public Map<String,String> getOptions()
    {
        return options;
    }

    public static IServerProtocol protocolFactory(String host, Integer port, Map<String,String> options, Long epoch)
    throws NetworkException
    {
        return new RedisSequencerProtocol(host, port, options);
    }

    public RedisSequencerProtocol(String host, Integer port, Map<String,String> options)
    throws NetworkException
    {
        this.host = host;
        this.port = port;
        if (options == null)
        {
            this.options = new HashMap<String,String>();
            this.options.put("key", "corfudb_seq");
        }
        else {
            this.options = options;
        }

        try
        {
            this.pool = new JedisPool(host, port);
        }
        catch (Exception ex)
        {
            log.warn("Failed to connect to endpoint " + getFullString());
            throw new NetworkException("Failed to connect to endpoint", this);
        }
    }

    public long sequenceGetNext()
    throws NetworkException
    {
        try (Jedis jedis = pool.getResource())
        {
            return jedis.incr(options.get("key"));
        }
    }

    public long sequenceGetNext(int numTokens)
    throws NetworkException
    {
        try (Jedis jedis = pool.getResource())
        {
            return jedis.incrBy(options.get("key"), numTokens);
        }
    }


    public long sequenceGetCurrent()
    throws NetworkException
    {
        try (Jedis jedis = pool.getResource())
        {
            return Long.parseLong(jedis.get(options.get("key")));
        }
    }

    @Override
    public void recover(long lastPos) throws NetworkException {
        try (Jedis jedis = pool.getResource())
        {
            jedis.set(options.get("key"), Long.toString(lastPos));
        }
    }

    public boolean ping()
    {
        try (Jedis jedis = pool.getResource())
        {
            if (jedis.ping().equals("PONG"))
            {
                return true;
            }
            return false;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    public void setEpoch(long epoch)
    {

    }

    public void reset(long epoch)
    throws NetworkException
    {
        try (Jedis jedis = pool.getResource())
        {
            jedis.set(options.get("key"), "0");
        }
    }
}


