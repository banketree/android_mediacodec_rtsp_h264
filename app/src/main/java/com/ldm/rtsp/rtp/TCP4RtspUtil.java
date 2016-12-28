package com.ldm.rtsp.rtp;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoEventType;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.executor.ExecutorFilter;
import org.apache.mina.transport.socket.nio.NioSocketConnector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
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
    private AtomicInteger seqState = new AtomicInteger(1);
    private Map<String, String> attribute;
    private String mediaUrl;
    private TCP4RtspUtil.RequestEntry entry;
    private VideoStreamInterface listener;

    public TCP4RtspUtil(String mediaUrl, VideoStreamInterface listener) {
        this.mediaUrl = mediaUrl;
        this.listener = listener;
        this.status = new AtomicBoolean(false);
        this.attribute = new HashMap();
    }

    private void parseUrl(String mediaUrl) {
        String hostname = mediaUrl.replaceAll("rtsp://", "");
        hostname = hostname.substring(0, hostname.indexOf("/"));
        String[] arr = hostname.split(":");
        this.host = arr[0];
        this.port = Integer.valueOf(arr[1]).intValue();
    }

    public void doStart() throws IOException {
        if (this.status.compareAndSet(false, true)) {
            this.parseUrl(this.mediaUrl);
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
            this.initVedio();
        }

    }

    public void doStop() {
        if (this.status.compareAndSet(true, false)) {
            this.listener.releaseResource();
            this.connector.dispose(false);
        }

    }

    private void initVedio() throws IOException {
        RtpRequestModel request = new RtpRequestModel();
        request.setMethod("OPTIONS");
        request.setUri(this.mediaUrl);
        request.setVersion("RTSP/1.0");
        this.options(request, 15000L);
        request.setMethod("DESCRIBE");
        request.getHeaders().clear();
        this.discribe(request, 15000L);
    }

    private RtpResponseModel options(RtpRequestModel message, long timeout) throws IOException {
        if (this.seqState.get() == 1) {
            this.entry = new RequestEntry();
            message.getHeaders().put("CSeq", this.seqState.get() + "");
            this.session.write(message);
            return (RtpResponseModel) this.entry.get(timeout);
        } else {
            throw new IOException("invalidata request");
        }
    }

    private RtpResponseModel discribe(RtpRequestModel message, long timeout) throws IOException {
        if (this.seqState.get() != 2) {
            throw new IOException("valida seqState :" + this.seqState.get());
        } else {
            this.entry = new TCP4RtspUtil.RequestEntry();
            message.getHeaders().put("CSeq", this.seqState.get() + "");
            message.getHeaders().put("Accept", "application/sdp");
            this.session.write(message);
            RtpResponseModel resp = (RtpResponseModel) this.entry.get(timeout);
            if (resp != null) {
                String bodyLine = new String(resp.getBody());
                this.attribute.put("base_line", bodyLine);
                StringTokenizer st = new StringTokenizer(bodyLine, "\r\n");

                String control;
                while (st.hasMoreTokens()) {
                    control = st.nextToken().trim();
                    if (control.startsWith("m=audio")) {
                        break;
                    }

                    if (control.contains("a=range:")) {
                        this.attribute.put("Range", control.substring(control.indexOf(":") + 1));
                    } else if (control.contains("a=control:")) {
                        this.attribute.put("Control", control.substring(control.indexOf(":") + 1));
                    }
                }

                control = (String) this.attribute.get("Control");
                if (control != null && !"".equals(control.trim()) && !control.startsWith("rtsp://")) {
                    this.attribute.put("Control", this.mediaUrl + "/" + control.trim());
                }
            }

            return resp;
        }
    }

    private void setup(long timeout) throws IOException {
        if (this.seqState.get() == 3) {
            this.entry = new TCP4RtspUtil.RequestEntry();
            RtpRequestModel setup = new RtpRequestModel();
            setup.setMethod("SETUP");
            setup.setUri((String) this.attribute.get("Control"));
            setup.setVersion("RTSP/1.0");
            setup.getHeaders().put("Transport", "RTP/AVP/TCP;unicast;interleaved=2-3");
            setup.getHeaders().put("CSeq", this.seqState.get() + "");
            this.session.write(setup).addListener(new IoFutureListener() {
                public void operationComplete(IoFuture future) {
                    future.getSession().removeAttribute("streamId");
                }
            });
            RtpResponseModel resp = (RtpResponseModel) this.entry.get(timeout);
            if (resp != null) {
                this.attribute.put("Session", ((String) resp.getHeaders().get("session")).split(";")[0]);
            }

        } else {
            throw new IOException("valida seqState :" + this.seqState.get());
        }
    }

    public void play() throws IOException {
        this.setup(15000L);
        if (this.seqState.get() == 4) {
            this.entry = null;
            RtpRequestModel play = new RtpRequestModel();
            play.setMethod("PLAY");
            play.setUri((String) this.attribute.get("Control"));
            play.setVersion("RTSP/1.0");
            play.getHeaders().put("CSeq", this.seqState.get() + "");
            play.getHeaders().put("Session", this.attribute.get("Session"));
            play.getHeaders().put("Range", this.attribute.get("Range") == null ? "npt=0.00-" : (String) this.attribute.get("Range"));
            this.session.setAttribute("streamId", Integer.valueOf(1));
            this.session.write(play);
        } else {
            throw new IOException("valida seqState :" + this.seqState.get());
        }
    }

    public void pause() {
        this.seqState.compareAndSet(4, 5);
    }

    public void consume() {
        this.seqState.compareAndSet(5, 4);
    }

    public void tearDown() throws IOException {
        if (this.seqState.get() == 4) {
            final CountDownLatch latch = new CountDownLatch(1);
            RtpRequestModel stop = new RtpRequestModel();
            this.entry = null;
            stop.setMethod("TEARDOWN");
            stop.setUri((String) this.attribute.get("Control"));
            stop.setVersion("RTSP/1.0");
            stop.getHeaders().put("CSeq", this.seqState.get() + 1 + "");
            stop.getHeaders().put("Session", this.attribute.get("Session"));
            this.session.write(stop).addListener(new IoFutureListener() {
                public void operationComplete(IoFuture future) {
                    future.getSession().setAttribute("streamId", Integer.valueOf(3));
                    TCP4RtspUtil.this.seqState.decrementAndGet();
                    latch.countDown();
                }
            });

            try {
                latch.await(15000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException var4) {
                throw new IOException(var4);
            }
        } else {
            throw new IOException("invalidata request");
        }
    }

    public Map<String, String> getAttribute() {
        return this.attribute;
    }

    public void setAttribute(Map<String, String> attribute) {
        this.attribute = attribute;
    }

    public String getMediaUrl() {
        return this.mediaUrl;
    }

    public void setMediaUrl(String mediaUrl) {
        this.mediaUrl = mediaUrl;
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

        public void sessionClosed(IoSession session) throws Exception {
            super.sessionClosed(session);
            TCP4RtspUtil.this.doStop();
        }

        public void messageReceived(IoSession session, Object message) throws Exception {
            if (message instanceof RtpResponseModel) {
                RtpResponseModel rtp = (RtpResponseModel) message;
                if (TCP4RtspUtil.this.entry != null) {
                    TCP4RtspUtil.this.entry.fillResp(rtp);
                }
            } else if (message instanceof byte[] && TCP4RtspUtil.this.listener != null && TCP4RtspUtil.this.seqState.get() == 4) {
                TCP4RtspUtil.this.listener.onVideoStream((byte[]) ((byte[]) message));
            }

        }
    }
}
