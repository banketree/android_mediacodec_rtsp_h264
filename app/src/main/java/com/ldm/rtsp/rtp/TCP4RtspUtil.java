package com.ldm.rtsp.rtp;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoEventType;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.executor.ExecutorFilter;
import org.apache.mina.transport.socket.nio.NioSocketConnector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TCP4RtspUtil implements RtspInterface {
    private int port;
    private String host;
    private AtomicBoolean status;
    private NioSocketConnector connector;
    private IoSession session;
    private AtomicInteger seqState = new AtomicInteger(2);
    private TCP4RtspUtil.RequestEntry entry;
    private VideoStreamInterface listener;

    public TCP4RtspUtil(String host, int port, VideoStreamInterface listener) {
        this.listener = listener;
        this.host = host;
        this.port = port;
        this.status = new AtomicBoolean(false);
    }

    public void doStart() throws IOException {
        if (this.status.compareAndSet(false, true)) {
            this.connector = new NioSocketConnector();
            this.connector.setConnectTimeoutMillis(5000L);
            this.connector.getSessionConfig().setReadBufferSize(8192);
            this.connector.getSessionConfig().setMaxReadBufferSize(65536);
            this.connector.getSessionConfig().setReceiveBufferSize(65536);
            this.connector.getSessionConfig().setSendBufferSize(65536);
            this.connector.getFilterChain().addLast("codec", new ProtocolCodecFilter(new RtpEncoder(), new RtpDecoder()));
            this.connector.getFilterChain().addLast("_io_c_write", new ExecutorFilter(8, new IoEventType[]{IoEventType.WRITE}));
            this.connector.setHandler(new TCP4RtspUtil.TcpMediaClientHandler());
            ConnectFuture future = this.connector.connect(new InetSocketAddress(this.host, this.port));
            future.awaitUninterruptibly();
            this.session = future.getSession();
        }

    }

    public void doStop() {
        if (this.status.compareAndSet(true, false)) {
            this.listener.releaseResource();
            this.connector.dispose(false);
        }
    }


    class RequestEntry {
        Object message;
        CountDownLatch latch = new CountDownLatch(1);

        RequestEntry() {
        }

        public void fillResp(Object message) {
            this.message = message;
            TCP4RtspUtil.this.seqState.incrementAndGet();
            this.latch.countDown();
        }

        public Object get(long timeout) throws IOException {
            try {
                this.latch.await(timeout, TimeUnit.MILLISECONDS);
            } catch (Exception var4) {
                throw new IOException(var4);
            }

            if (this.message == null) {
                throw new IOException("waite resp timout[" + timeout + "]");
            } else {
                return this.message;
            }
        }
    }

    class TcpMediaClientHandler extends IoHandlerAdapter {
        TcpMediaClientHandler() {
        }

        public void exceptionCaught(IoSession session, Throwable cause) throws Exception {
            cause.printStackTrace();
        }

        public void messageReceived(IoSession session, Object message) throws Exception {
            if (message instanceof RtpResponseModel) {
                RtpResponseModel rtp = (RtpResponseModel) message;
                if (TCP4RtspUtil.this.entry != null) {
                    TCP4RtspUtil.this.entry.fillResp(rtp);
                }
            } else if (message instanceof byte[] && TCP4RtspUtil.this.listener != null) {
                TCP4RtspUtil.this.listener.onVideoStream((byte[]) ((byte[]) message));
            }

        }
    }

    public RtpResponseModel options(RtpRequestModel message, long timeout) throws IOException {
        if (this.seqState.get() == 2) {
            this.entry = new TCP4RtspUtil.RequestEntry();
            message.getHeaders().put("CSeq", this.seqState.get() + "");
            this.session.write(message);
            return (RtpResponseModel) this.entry.get(timeout);
        } else {
            throw new IOException("invalidata request");
        }
    }

    public RtpResponseModel describe(RtpRequestModel message, long timeout) throws IOException {
        if (this.seqState.get() == 3) {
            this.entry = new TCP4RtspUtil.RequestEntry();
            message.getHeaders().put("CSeq", this.seqState.get() + "");
            this.session.write(message);
            return (RtpResponseModel) this.entry.get(timeout);
        } else {
            throw new IOException("invalidata request");
        }
    }

    public RtpResponseModel setup(RtpRequestModel message, long timeout) throws IOException {
        if (this.seqState.get() == 4) {
            this.entry = new TCP4RtspUtil.RequestEntry();
            message.getHeaders().put("CSeq", this.seqState.get() + "");
            this.session.write(message);
            return (RtpResponseModel) this.entry.get(timeout);
        } else {
            throw new IOException("invalidata request");
        }
    }

    public void play(RtpRequestModel message) throws IOException {
        if (this.seqState.get() == 5) {
            this.entry = new TCP4RtspUtil.RequestEntry();
            message.getHeaders().put("CSeq", this.seqState.get() + "");
            this.session.setAttribute("streamId", Integer.valueOf(1));
            this.session.write(message);
        } else {
            throw new IOException("invalidata request");
        }
    }
}
