package com.signalquest.example;

import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.datastore.core.Serializer;
import androidx.datastore.rxjava3.RxDataStore;
import androidx.datastore.rxjava3.RxDataStoreBuilder;

import com.signalquest.api.NtripParser;
import com.signalquest.api.NtripParser.AuthorizationFailure;
import com.signalquest.api.NtripParser.AuthorizationResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Queue;

import io.reactivex.rxjava3.core.Single;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

/**
 * NTRIP Service connector.
 * <p>
 * Connects to an NTRIP service, parses the authorization header, and listens for RTCM messages. Will
 * also, optionally, send the current phone position as a GGA message, on a timer.
 * <p>
 * The {@link #connect(NtripService)} kicks off the normal flow (managed by a state machine) of:
 * {@link #startAiding()}, {@link #handleAuthorized(byte[])}, followed by multiple calls to
 * {@link NtripParser#parseRtcm(byte[])}, with data available using {@link NtripParser#next(int)}.
 */
class Ntrip {
    private final static int GGA_INTERVAL_MILLISECONDS = 5000;
    public enum State { IDLE, CONNECTING, AUTHORIZING, ACTIVE }
    public State getState() {
        return _state;
    }
    private State _state = State.IDLE;
    private final static String LOG_TAG = "NTRIP";
    private final NtripParser parser;
    private NtripGga gga = null;
    private Handler ggaHandler;
    private Server ntripServer;
    static final String NTRIP_CONNECT_ACTION = "com.signalquest.example.NTRIP_CONNECT_ACTION";
    static final String NTRIP_DISCONNECT_ACTION = "com.signalquest.example.NTRIP_DISCONNECT_ACTION";

    private final ServerListener serverListener = new ServerListener() {
        @Override
        public void handleData(byte[] data) {
            switch (_state) {
                case AUTHORIZING:
                    Log.i(LOG_TAG, "Parsing authorized");
                    handleAuthorized(data);
                    break;
                case ACTIVE:
                    Log.v(LOG_TAG, "Parsing incoming RTCM");
                    try {
                        parser.parseRtcm(data);
                        App.onParsed();
                    } catch (NtripParser.ParseException e) {
                        // Not expected; please report the log results to SignalQuest.
                        App.displayError(LOG_TAG, "NTRIP RTCM parse failure; see logs");
                        Log.e(LOG_TAG, "Failed parsing RTCM data", e);
                    }
                    break;
                default:
                    String str = new String(data);
                    Log.w(LOG_TAG, "Unhandled state " + _state.name() + ", data: " + str.length() + " bytes: (" + str + ")");
            }
        }

        @Override
        public void connected() {
            Ntrip.this.startAiding();
        }

        @Override
        public void handleException(Exception e) {
            if (_state != State.IDLE) {
                disconnect();
            }
            App.displayError(LOG_TAG, e.toString(), e);
        }
    };

    /**
     * Sets up the {@link NtripParser}.
     */
    public Ntrip() {
        this.parser = new NtripParser();
    }

    /**
     * Connects to the given NTRIP service.
     */
    public void connect(NtripService service) {
        setNtripService(service);
        if (service.getMountpoint() == null || service.getMountpoint().isEmpty()) {
            Log.w(LOG_TAG, "This example app does not handle mountpoint listings");
            App.displayError(LOG_TAG, "Mountpoint missing");
            return;
        }

        try {
            ntripServer = new Server(service.getServer(), service.getPort(), serverListener);
            ntripServer.start();
            _state = State.CONNECTING;
            broadcastConnect();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Unhandled: unable to connect to server");
            _state = State.IDLE;
        }
    }

    public byte[] next(int maxLength) {
        return parser.next(maxLength);
    }

    /**
     * Disconnect and broadcast for UI; this class will self-disconnect for errors.
     */
    public void disconnect() {
        _state = State.IDLE;
        stopGgaTimer();
        if (ntripServer != null) {
            ntripServer.stop();
        }
        broadcastDisconnect();
    }

    NtripService getNtripService() {
        return serviceStore.data().firstElement().blockingGet();
    }

    private void setNtripService(@NonNull NtripService service) {
        serviceStore.updateDataAsync(currentService -> Single.just(service));
    }

    private void sendGgaString() {
        NtripService ntripService = getNtripService();
        if (ntripService != null && ntripService.getSendPosition() && _state == State.ACTIVE) {
            if (gga == null) {
                gga = new NtripGga();
            }

            String gga = this.gga.toString();
            if (gga.isEmpty()) {
                Log.w(LOG_TAG, "No position to send to NTRIP server");
                return;
            }
            String serverRequest = gga + "\r\n";
            byte[] data = serverRequest.getBytes();
            try {
                ntripServer.write(data);
            } catch (Exception e) {
                App.displayError(LOG_TAG, "Error writing GGA to NTRIP server", e);
            }
        }
    }

    private final Runnable ggaPoster = new Runnable() {
        @Override
        public void run() {
            if (_state != State.ACTIVE) {
                return;
            }
            sendGgaString();
            ggaHandler.postDelayed(this, GGA_INTERVAL_MILLISECONDS);

        }
    };

    private void startGgaTimer() {
        if (ggaHandler == null) {
            HandlerThread ggaHandlerThread = new HandlerThread("gga");
            ggaHandlerThread.start();
            ggaHandler = new Handler(ggaHandlerThread.getLooper());
        }
        ggaHandler.postDelayed(ggaPoster, GGA_INTERVAL_MILLISECONDS);
    }

    private void stopGgaTimer() {
        if (ggaHandler != null) {
            ggaHandler.removeCallbacks(ggaPoster);
        }
    }

    private static String getSlashedMountpoint(NtripService service) {
        String mountpoint = service.getMountpoint();
        return mountpoint.startsWith("/") ? mountpoint : "/" + mountpoint;
    }

    private void startAiding() {
        NtripService ntripService = getNtripService();
        if (ntripService != null) {
            Log.i(LOG_TAG, ("Authorize for " + ntripService));
            _state = State.AUTHORIZING;
            // NOTE: Please use your own user-agent string
            String serverRequest = "GET " + getSlashedMountpoint(ntripService) + " HTTP/1.1\r\nHost: " + ntripService.getServer() + "\r\nAccept: */*\r\nUser-Agent: SignalQuest NTRIP Client/1.0\r\nAuthorization: Basic " + getBasicAuth() + "\r\nConnection: close\r\n\r\n";
            byte[] data = serverRequest.getBytes();
            try {
                ntripServer.write(data);
            } catch (Exception e) {
                App.displayError(LOG_TAG, "Error creating NTRIP aiding server request", e);
                disconnect();
            }
        } else {
            App.displayError(LOG_TAG, "Missing ntripService");
        }
    }

    private String getBasicAuth() {
        NtripService ntripService = getNtripService();
        String username = ntripService.getUsername();
        String password = ntripService.getPassword();
        String authString = username + ":" + password;
        return Base64.getEncoder().encodeToString(authString.getBytes());
    }

    private void handleAuthorized(byte[] serverResponse) {
        try {
            AuthorizationResult result = parser.parseAuthorized(serverResponse);
            switch (result) {
                case SUCCESS:
                    Log.i(LOG_TAG, "NTRIP auth success, active");
                    _state = State.ACTIVE;
                    NtripService ntripService = getNtripService();
                    if (ntripService.getSendPosition()) {
                        // Start a repeating timer to send the latest GGA string (if any known)
                        startGgaTimer();
                    }
                    break;
                case INSUFFICIENT_DATA:
                    // Stay in State.AUTHORIZING so next response chunk will be sent to parseAuthorized, too.
                    break;
            }
        } catch (AuthorizationFailure e) {
            App.displayError(LOG_TAG, "NTRIP auth failure: (" + e.summary + ": " + e.details + ")");
            disconnect();
        }
    }

    private void broadcastConnect() {
        App.getAppContext().sendBroadcast(new Intent(NTRIP_CONNECT_ACTION));
    }

    private void broadcastDisconnect() {
        App.getAppContext().sendBroadcast(new Intent(NTRIP_DISCONNECT_ACTION));
    }

    /**
     * Simple server that connects to, reads from, and writes to a socket.
     */
    private static class Server implements Runnable {

        private final Handler handler;
        private final String serverAddress;
        private final int serverPort;
        private SocketChannel socketChannel;
        private Selector selector;
        private volatile boolean running = false;
        private final ServerListener listener;
        Queue<ByteBuffer> writeBuffer = new ArrayDeque<>();

        public Server(String serverAddress, int serverPort, ServerListener listener) {
            this.serverAddress = serverAddress;
            this.serverPort = serverPort;
            this.listener = listener;
            HandlerThread thread = new HandlerThread("ntrip server");
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        public void run() {
            try {
                selector = Selector.open();
                socketChannel = SocketChannel.open();
                socketChannel.configureBlocking(false);
                socketChannel.register(selector, SelectionKey.OP_CONNECT);
                InetSocketAddress address = new InetSocketAddress(serverAddress, serverPort);
                socketChannel.connect(address);
                while (running) {
                    selector.select();
                    if (!selector.isOpen()) {
                        break;
                    }
                    for (SelectionKey key : selector.selectedKeys()) {
                        if (key.isConnectable()) {
                            if (socketChannel.isConnectionPending()) {
                                socketChannel.finishConnect();
                                socketChannel.register(selector, SelectionKey.OP_READ);
                                listener.connected();
                            }
                        } else if (key.isReadable()) {
                            ByteBuffer buffer = ByteBuffer.allocate(1024);
                            int bytesRead = socketChannel.read(buffer);
                            if (bytesRead == -1) {
                                throw new RuntimeException("Server has closed the connection");
                            }
                            buffer.flip();
                            byte[] response = new byte[bytesRead];
                            buffer.get(response);
                            listener.handleData(response);
                            buffer.clear();
                        } else if (key.isWritable()) {
                            ByteBuffer buffer = writeBuffer.peek();
                            if (buffer != null) {
                                socketChannel.write(buffer);
                                if (buffer.remaining() == 0) {
                                    writeBuffer.poll();
                                }
                            }
                            if (writeBuffer.isEmpty()) {
                                key.interestOps(SelectionKey.OP_READ);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                listener.handleException(e);
            } finally {
                stop();
            }
        }

        public void start() {
            running = true;
            handler.post(this);
        }

        public void stop() {
            running = false;
            try {
                if (socketChannel != null && socketChannel.isOpen()) {
                    socketChannel.close();
                }
                if (selector != null && selector.isOpen()) {
                    selector.wakeup();
                    selector.close();
                }
            } catch (IOException e) {
                listener.handleException(e);
            }
        }

        public void write(byte[] data) {
            assert(running);
            if (socketChannel != null && socketChannel.isConnected()) {
                writeBuffer.add(ByteBuffer.wrap(data));
                socketChannel.keyFor(selector).interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                selector.wakeup();
            } else {
                listener.handleException(new IOException("Socket channel is not connected"));
            }
        }
    }

    /**
     * Used by the {@link Server} for reporting read data and exceptions, and reporting the connected event.
     */
    private interface ServerListener {
        void handleData(byte[] data);
        void connected();
        void handleException(Exception e);
    }

    /**
     * For serializing the last used NTRIP service, uses Proto DataStore
     */
    private static class ServiceSerializer implements Serializer<NtripService> {
        @Override
        public NtripService getDefaultValue() {
            return NtripService.getDefaultInstance();
        }

        @Nullable
        @Override
        public NtripService readFrom(@NonNull InputStream inputStream, @NonNull Continuation<? super NtripService> continuation) {
            try {
                return NtripService.parseFrom(inputStream);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Nullable
        @Override
        public NtripService writeTo(NtripService ntripService, @NonNull OutputStream outputStream, @NonNull Continuation<? super Unit> continuation) {
            try {
                ntripService.writeTo(outputStream);
                return ntripService;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    RxDataStore<NtripService> serviceStore =
            new RxDataStoreBuilder<>(App.getAppContext(), "service.pb", new ServiceSerializer()).build();
}



