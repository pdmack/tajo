/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.rpc;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tajo.util.NetUtils;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;

public abstract class NettyClientBase implements Closeable {
  private static Log LOG = LogFactory.getLog(NettyClientBase.class);

  protected ClientBootstrap bootstrap;
  private ChannelFuture channelFuture;

  public NettyClientBase() {
  }

  public abstract <T> T getStub();
  public abstract RpcConnectionPool.RpcConnectionKey getKey();

  public void init(InetSocketAddress addr, ChannelPipelineFactory pipeFactory, ClientSocketChannelFactory factory)
      throws IOException {
    try {

      this.bootstrap = new ClientBootstrap(factory);
      this.bootstrap.setPipelineFactory(pipeFactory);
      // TODO - should be configurable
      this.bootstrap.setOption("connectTimeoutMillis", 10000);
      this.bootstrap.setOption("connectResponseTimeoutMillis", 10000);
      this.bootstrap.setOption("receiveBufferSize", 1048576 * 10);
      this.bootstrap.setOption("tcpNoDelay", true);
      this.bootstrap.setOption("keepAlive", true);

      connect(addr);
    } catch (Throwable t) {
      close();
      throw new IOException("Connect error to " + addr + " cause " + t.getMessage(), t.getCause());
    }
  }

  public void connect(InetSocketAddress addr) {
    if(addr.isUnresolved()){
       addr = NetUtils.createSocketAddr(addr.getHostName(), addr.getPort());
    }
    this.channelFuture = bootstrap.connect(addr);
    this.channelFuture.awaitUninterruptibly();
    if (!channelFuture.isSuccess()) {
      channelFuture.getCause().printStackTrace();
      throw new RuntimeException(channelFuture.getCause());
    }
  }

  public boolean isConnected() {
    return getChannel().isConnected();
  }

  public InetSocketAddress getRemoteAddress() {
    return (InetSocketAddress) channelFuture.getChannel().getRemoteAddress();
  }

  public Channel getChannel() {
    return channelFuture.getChannel();
  }

  @Override
  public void close() {
    if(this.channelFuture != null && getChannel().isOpen()) {
      try {
        getChannel().close().awaitUninterruptibly();
      } catch (Throwable ce) {
        LOG.warn(ce);
      }
    }

    if(this.bootstrap != null) {
      // This line will shutdown the factory
      // this.bootstrap.releaseExternalResources();
      if(LOG.isDebugEnabled()) {
        LOG.debug("Proxy is disconnected from " +
            getRemoteAddress().getHostName() + ":" + getRemoteAddress().getPort());
      }
    }
  }
}
