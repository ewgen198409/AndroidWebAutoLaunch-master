package uk.me.hardill.weblauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.format.Formatter;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

/**
 * DLNA Audio Renderer Service for Android
 * Simple SSDP-based UPnP implementation for device discovery
 */
public class UpnpAudioRendererService extends Service {
    private static final String TAG = "DLNARenderer";
    private static final String CHANNEL_ID = "dlna_renderer_channel";

    // SSDP constants
    private static final String SSDP_IP = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String UPNP_ROOT_DEVICE = "upnp:rootdevice";
    private static final String MEDIA_RENDERER_NT = "urn:schemas-upnp-org:device:MediaRenderer:1";

    private MediaPlayer mediaPlayer;
    private Handler handler;
    private int volume = 50;
    private String currentUri = "";
    private String currentMetaData = "";
    
    // Добавлены поля для метаданных
    private String mediaTitle = "";
    private String mediaArtist = "";
    private String mediaContentType = "audio/mpeg";
    
    private String transportState = "STOPPED"; // STOPPED, PLAYING, PAUSED_PLAYBACK
    private int mediaDurationMs = 0; // Cached duration in ms
    private int mediaPositionMs = 0; // For simplicity, update on query if playing

    // SSDP discovery
    private MulticastSocket multicastSocket;
    private DatagramSocket unicastSocket;
    private Thread ssdpThread;
    private boolean isRunning = false;
    private String localIP = "";
    private String deviceUUID = java.util.UUID.randomUUID().toString();
    private List<String> avTransportCallbacks = new ArrayList<>();
    private List<String> renderingControlCallbacks = new ArrayList<>();

    // HTTP server for SOAP control
    private ServerSocket httpServerSocket;
    private Thread httpServerThread;
    private boolean httpServerRunning = false;
    private static final int HTTP_PORT = 8080;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "=== DLNA Audio Renderer Service CREATED ===");

        handler = new Handler(Looper.getMainLooper());
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        setupMediaPlayerListeners();
        createNotificationChannel();
        startForeground(1, createNotification());

        Log.i(TAG, "MediaPlayer and notification setup complete");

        // Initialize SSDP discovery
        try {
            Log.i(TAG, "Attempting to get local IP address...");
            localIP = getLocalIpAddress();
            Log.i(TAG, "Local IP result: " + localIP);

            if (localIP != null && !localIP.isEmpty()) {
                Log.i(TAG, "Starting SSDP discovery...");
                startSsdpDiscovery();
                Log.i(TAG, "Starting HTTP server...");
                startHttpServer();
                Log.i(TAG, "DLNA Audio Renderer initialized with SSDP discovery - IP: " + localIP + ", UUID: " + deviceUUID);
                updateNotification("DLNA Renderer Active (Discoverable)", "", "");
            } else {
                Log.w(TAG, "Could not determine local IP address - SSDP disabled");
                updateNotification("DLNA Renderer Ready (No network)", "", "");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SSDP discovery", e);
            updateNotification("DLNA Renderer Error", "", "");
        }
    }

    private void setupMediaPlayerListeners() {
        mediaPlayer.setOnPreparedListener(mp -> {
            Log.i(TAG, "Media prepared, starting playback");
            mediaDurationMs = mp.getDuration();
            mp.start();
            transportState = "PLAYING";
            String title = !mediaTitle.isEmpty() ? mediaTitle : getUriFilename(currentUri);
            updateNotification("Playing", title, mediaArtist);
            Log.i(TAG, "Transport state changed to: PLAYING");
            notifyAvTransportChange();
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            Log.i(TAG, "Playback completed");
            transportState = "STOPPED";
            mediaDurationMs = 0;
            updateNotification("Stopped", "", "");
            Log.i(TAG, "Transport state changed to: STOPPED");
            notifyAvTransportChange();
        });

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "Media player error: " + what + ", " + extra);
            transportState = "STOPPED";
            mediaDurationMs = 0;
            updateNotification("Playback Error", "", "");
            Log.i(TAG, "Transport state changed to: STOPPED (error)");
            notifyAvTransportChange();
            return true;
        });
    }

    private String getUriFilename(String uri) {
        if (uri == null) return "Unknown";
        int lastSlash = uri.lastIndexOf('/');
        return lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri;
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DLNA Audio Renderer")
                .setContentText("Basic mode - UPnP pending")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String status, String title, String artist) {
        String contentText;
        if (!title.isEmpty()) {
            if (!artist.isEmpty()) {
                contentText = status + ": " + artist + " - " + title;
            } else {
                contentText = status + ": " + title;
            }
        } else {
            contentText = status;
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DLNA Audio Renderer")
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(1, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "DLNA Audio Renderer",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("DLNA Audio Renderer Service");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "DLNA Audio Renderer Service started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "DLNA Audio Renderer Service destroyed");

        stopHttpServer();
        stopSsdpDiscovery();

        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Public methods for future UPnP integration
    public void setCurrentUri(String uri) {
        this.currentUri = uri;
        Log.i(TAG, "URI set: " + uri);
    }

    public void playMedia() {
        Log.i(TAG, "Play requested, current URI: " + currentUri + ", transportState: " + transportState);

        if (currentUri.isEmpty()) {
            Log.w(TAG, "No URI set for playback");
            updateNotification("No source set", "", "");
            return;
        }

        handler.post(() -> {
            try {
                if (mediaPlayer.isPlaying()) {
                    Log.i(TAG, "Already playing → ignoring or force restart if needed");
                    return;
                }

                if ("PAUSED_PLAYBACK".equals(transportState)) {
                    mediaPlayer.start();
                    transportState = "PLAYING";
                    String title = !mediaTitle.isEmpty() ? mediaTitle : getUriFilename(currentUri);
                    updateNotification("Playing", title, mediaArtist);
                    Log.i(TAG, "Resumed from pause");
                } else {
                    // STOPPED, TRANSITIONING или неизвестное → всегда новый запуск
                    mediaPlayer.reset();
                    mediaPlayer.setDataSource(currentUri);
                    mediaPlayer.prepareAsync();
                    transportState = "TRANSITIONING";
                    String title = !mediaTitle.isEmpty() ? mediaTitle : getUriFilename(currentUri);
                    updateNotification("Loading", title, mediaArtist);
                    Log.i(TAG, "Starting playback");
                }
                notifyAvTransportChange();
            } catch (Exception e) {
                Log.e(TAG, "Play failed", e);
                transportState = "STOPPED";
                updateNotification("Playback failed", "", "");
                notifyAvTransportChange();
            }
        });
    }

    public void pauseMedia() {
        try {
            if ("PLAYING".equals(transportState) && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                transportState = "PAUSED_PLAYBACK";
                String title = !mediaTitle.isEmpty() ? mediaTitle : getUriFilename(currentUri);
                updateNotification("Paused", title, mediaArtist);
                Log.i(TAG, "Playback paused");
                notifyAvTransportChange();
            } else {
                Log.i(TAG, "Not playing or not in PLAYING state, cannot pause. Current state: " + transportState);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error pausing playback", e);
            transportState = "STOPPED";
            notifyAvTransportChange();
        }
    }

    public void stopMedia() {
        try {
            if (!"STOPPED".equals(transportState)) {
                mediaPlayer.stop();
                transportState = "STOPPED";
                mediaDurationMs = 0;
                updateNotification("Stopped", "", "");
                Log.i(TAG, "Playback stopped");
                notifyAvTransportChange();
            } else {
                Log.i(TAG, "Already stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping playback", e);
            transportState = "STOPPED";
            notifyAvTransportChange();
        }
    }

    public void setMediaVolume(int newVolume) {
        volume = Math.max(0, Math.min(100, newVolume));
        float volumeFloat = volume / 100.0f;
        mediaPlayer.setVolume(volumeFloat, volumeFloat);
        Log.i(TAG, "Volume set to: " + volume);
        notifyRenderingControlChange();
    }

    public int getMediaVolume() {
        return volume;
    }

    public boolean isMediaPlaying() {
        return mediaPlayer.isPlaying();
    }

    public String getCurrentUri() {
        return currentUri;
    }

    // Добавлены геттеры для метаданных
    public String getMediaTitle() {
        return mediaTitle;
    }

    public String getMediaArtist() {
        return mediaArtist;
    }

    public String getMediaContentType() {
        return mediaContentType;
    }

    // SSDP Discovery Methods
    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
                if (ipAddress != 0) {
                    return Formatter.formatIpAddress(ipAddress);
                }
            }

            // Fallback: enumerate network interfaces
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') == -1) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get local IP address", e);
        }
        return null;
    }

    private void startSsdpDiscovery() {
        isRunning = true;
        ssdpThread = new Thread(() -> {
            try {
                // Create multicast socket for receiving M-SEARCH requests
                multicastSocket = new MulticastSocket(SSDP_PORT);
                multicastSocket.setReuseAddress(true);
                InetAddress group = InetAddress.getByName(SSDP_IP);
                multicastSocket.joinGroup(group);

                // Create unicast socket for sending responses
                unicastSocket = new DatagramSocket();

                Log.i(TAG, "SSDP discovery started on " + localIP + ":" + SSDP_PORT);

                byte[] buffer = new byte[1024];
                while (isRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        multicastSocket.receive(packet);

                        String received = new String(packet.getData(), 0, packet.getLength());
                        Log.d(TAG, "Received SSDP packet:\n" + received);

                        if (received.contains("M-SEARCH")) {
                            handleMSearchRequest(received, packet.getAddress(), packet.getPort());
                        }
                    } catch (Exception e) {
                        if (isRunning) {
                            Log.e(TAG, "Error in SSDP discovery loop", e);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start SSDP discovery", e);
            } finally {
                stopSsdpDiscovery();
            }
        });

        ssdpThread.setDaemon(true);
        ssdpThread.start();
    }

    private void stopSsdpDiscovery() {
        isRunning = false;

        if (ssdpThread != null) {
            ssdpThread.interrupt();
            ssdpThread = null;
        }

        if (multicastSocket != null) {
            try {
                multicastSocket.leaveGroup(InetAddress.getByName(SSDP_IP));
                multicastSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing multicast socket", e);
            }
            multicastSocket = null;
        }

        if (unicastSocket != null) {
            unicastSocket.close();
            unicastSocket = null;
        }

        Log.i(TAG, "SSDP discovery stopped");
    }

    private void handleMSearchRequest(String request, InetAddress clientAddress, int clientPort) {
        try {
            // Parse ST (Search Target) from request
            String st = extractHeaderValue(request, "ST");
            if (st == null) return;

            Log.i(TAG, "M-SEARCH request for ST: " + st + " from " + clientAddress.getHostAddress() + ":" + clientPort);

            // Check if we match the search target
            if ("ssdp:all".equals(st)) {
                // For ssdp:all, respond with all our device types
                Log.i(TAG, "MATCH: Responding to ST: ssdp:all with all device types");

                // Send response for root device
                sendSsdpResponse(clientAddress, clientPort, UPNP_ROOT_DEVICE);
                Log.i(TAG, "✓ SENT SSDP response for rootdevice");

                // Send response for MediaRenderer
                sendSsdpResponse(clientAddress, clientPort, MEDIA_RENDERER_NT);
                Log.i(TAG, "✓ SENT SSDP response for MediaRenderer");

            } else if (UPNP_ROOT_DEVICE.equals(st) || MEDIA_RENDERER_NT.equals(st) ||
                      "urn:schemas-upnp-org:device:MediaRenderer:*".equals(st)) {
                // Specific device type request
                Log.i(TAG, "MATCH: Responding to ST: " + st);
                sendSsdpResponse(clientAddress, clientPort, st);
                Log.i(TAG, "✓ SENT SSDP response to " + clientAddress.getHostAddress() + ":" + clientPort + " for ST: " + st);
            } else {
                Log.d(TAG, "NO MATCH: Ignoring ST: " + st);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling M-SEARCH request", e);
        }
    }

    private void sendSsdpResponse(InetAddress clientAddress, int clientPort, String st) {
        try {
            String response = buildSsdpResponse(st);

            byte[] responseBytes = response.getBytes("UTF-8");
            DatagramPacket responsePacket = new DatagramPacket(
                responseBytes, responseBytes.length, clientAddress, clientPort);

            unicastSocket.send(responsePacket);
            Log.d(TAG, "Sent SSDP response:\n" + response);
        } catch (Exception e) {
            Log.e(TAG, "Error sending SSDP response", e);
        }
    }

    private String buildSsdpResponse(String st) {
        StringBuilder response = new StringBuilder();

        response.append("HTTP/1.1 200 OK\r\n");
        response.append("CACHE-CONTROL: max-age=1800\r\n");
        response.append("LOCATION: http://").append(localIP).append(":8080/description.xml\r\n");
        response.append("SERVER: Android/UPnP/1.0 WebLauncher/1.0\r\n");
        response.append("ST: ").append(st).append("\r\n");
        response.append("USN: uuid:").append(deviceUUID).append("::").append(st).append("\r\n");
        response.append("EXT: \r\n");
        response.append("\r\n");

        return response.toString();
    }

    private String extractHeaderValue(String request, String headerName) {
        String[] lines = request.split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith(headerName.toLowerCase() + ":")) {
                return line.substring(headerName.length() + 1).trim();
            }
        }
        return null;
    }

    // HTTP Server Methods for SOAP Control
    private void startHttpServer() {
        httpServerRunning = true;
        httpServerThread = new Thread(() -> {
            try {
                httpServerSocket = new ServerSocket(HTTP_PORT);
                Log.i(TAG, "HTTP server started on port " + HTTP_PORT);

                while (httpServerRunning) {
                    try {
                        Socket clientSocket = httpServerSocket.accept();
                        handleHttpRequest(clientSocket);
                    } catch (Exception e) {
                        if (httpServerRunning) {
                            Log.e(TAG, "Error accepting HTTP connection", e);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start HTTP server", e);
            } finally {
                stopHttpServer();
            }
        });

        httpServerThread.setDaemon(true);
        httpServerThread.start();
    }

    private void stopHttpServer() {
        httpServerRunning = false;

        if (httpServerThread != null) {
            httpServerThread.interrupt();
            httpServerThread = null;
        }

        if (httpServerSocket != null) {
            try {
                httpServerSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing HTTP server socket", e);
            }
            httpServerSocket = null;
        }

        Log.i(TAG, "HTTP server stopped");
    }

    private void handleHttpRequest(Socket clientSocket) {
        try {
            InputStream input = clientSocket.getInputStream();
            OutputStream output = clientSocket.getOutputStream();

            // Read HTTP request
            byte[] buffer = new byte[8192];
            int bytesRead = input.read(buffer);
            String request = new String(buffer, 0, bytesRead);

            Log.d(TAG, "HTTP Request received:\n" + request);

            // Parse request line
            String[] lines = request.split("\r\n");
            if (lines.length > 0) {
                String requestLine = lines[0];
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) {
                    String method = parts[0];
                    String path = parts[1];

                    String response = handleHttpPath(method, path, request);
                    output.write(response.getBytes("UTF-8"));
                    Log.d(TAG, "HTTP Response sent");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling HTTP request", e);
        } finally {
            try {
                clientSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing client socket", e);
            }
        }
    }

    private String handleHttpPath(String method, String path, String request) {
        Log.i(TAG, "HTTP " + method + " " + path);

        if ("GET".equals(method) && "/description.xml".equals(path)) {
            return buildDeviceDescription();
        } else if ("GET".equals(method) && "/AVTransport/scpd.xml".equals(path)) {
            return buildAVTransportSCPD();
        } else if ("GET".equals(method) && "/RenderingControl/scpd.xml".equals(path)) {
            return buildRenderingControlSCPD();
        } else if ("SUBSCRIBE".equals(method) && path.contains("/AVTransport/event")) {
            return handleEventSubscription(request, avTransportCallbacks, "_AVTransport");
        } else if ("SUBSCRIBE".equals(method) && path.contains("/RenderingControl/event")) {
            return handleEventSubscription(request, renderingControlCallbacks, "_RenderingControl");
        } else if ("POST".equals(method) && (path.contains("AVTransport") || path.contains("RenderingControl"))) {
            return handleSoapRequest(request);
        } else {
            return buildHttpResponse(404, "text/plain", "Not Found");
        }
    }

    private String buildDeviceDescription() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String friendlyName = prefs.getString("renderer_name", "Android DLNA Media Player");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\r\n" +
            "  <specVersion>\r\n" +
            "    <major>1</major>\r\n" +
            "    <minor>0</minor>\r\n" +
            "  </specVersion>\r\n" +
            "  <device>\r\n" +
            "    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>\r\n" +
            "    <friendlyName>" + escapeXml(friendlyName) + "</friendlyName>\r\n" +
            "    <manufacturer>WebLauncher</manufacturer>\r\n" +
            "    <manufacturerURL>https://github.com</manufacturerURL>\r\n" +
            "    <modelDescription>DLNA Audio Renderer for Android</modelDescription>\r\n" +
            "    <modelName>AndroidDLNARenderer</modelName>\r\n" +
            "    <modelNumber>1.0</modelNumber>\r\n" +
            "    <modelURL>https://github.com</modelURL>\r\n" +
            "    <serialNumber>" + deviceUUID + "</serialNumber>\r\n" +
            "    <UDN>uuid:" + deviceUUID + "</UDN>\r\n" +
            "    <serviceList>\r\n" +
            "      <service>\r\n" +
            "        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>\r\n" +
            "        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>\r\n" +
            "        <controlURL>/AVTransport/control</controlURL>\r\n" +
            "        <eventSubURL>/AVTransport/event</eventSubURL>\r\n" +
            "        <SCPDURL>/AVTransport/scpd.xml</SCPDURL>\r\n" +
            "      </service>\r\n" +
            "      <service>\r\n" +
            "        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>\r\n" +
            "        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>\r\n" +
            "        <controlURL>/RenderingControl/control</controlURL>\r\n" +
            "        <eventSubURL>/RenderingControl/event</eventSubURL>\r\n" +
            "        <SCPDURL>/RenderingControl/scpd.xml</SCPDURL>\r\n" +
            "      </service>\r\n" +
            "    </serviceList>\r\n" +
            "  </device>\r\n" +
            "</root>\r\n";

        return buildHttpResponse(200, "text/xml", xml);
    }

    private String handleSoapRequest(String request) {
        Log.d(TAG, "SOAP Request received");

        try {
            // Extract SOAP action and body
            String soapAction = extractSoapAction(request);
            String soapBody = extractSoapBody(request);

            Log.i(TAG, "SOAP Action: " + soapAction);

            if (soapAction != null) {
                if (soapAction.contains("SetAVTransportURI")) {
                    return handleSetAVTransportURI(soapBody);
                } else if (soapAction.contains("Play")) {
                    return handlePlay(soapBody);
                } else if (soapAction.contains("Pause")) {
                    return handlePause(soapBody);
                } else if (soapAction.contains("Stop")) {
                    return handleStop(soapBody);
                } else if (soapAction.contains("GetTransportInfo")) {
                    return handleGetTransportInfo(soapBody);
                } else if (soapAction.contains("GetPositionInfo")) {
                    return handleGetPositionInfo(soapBody);
                } else if (soapAction.contains("SetVolume")) {
                    return handleSetVolume(soapBody);
                } else if (soapAction.contains("GetVolume")) {
                    return handleGetVolume(soapBody);
                } else if (soapAction.contains("Seek")) {
                    return handleSeek(soapBody);
                } else if (soapAction.contains("Next")) {
                    return handleNext(soapBody);
                } else if (soapAction.contains("Previous")) {
                    return handlePrevious(soapBody);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing SOAP request", e);
        }

        return buildSoapError(501, "Not Implemented");
    }

    private String handleSetAVTransportURI(String soapBody) {
        try {
            String uri = extractXmlValue(soapBody, "CurrentURI");
            String metaData = extractXmlValue(soapBody, "CurrentURIMetaData");

            if (uri != null) {
                setCurrentUri(uri);

                // Если метаданных нет или пусто — создаём минимальный валидный DIDL
                if (metaData == null || metaData.trim().isEmpty() || !metaData.trim().startsWith("<DIDL-Lite")) {
                    currentMetaData = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL/\" " +
                                      "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                                      "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                                      "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">" +
                                      "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                                      "<dc:title>Unknown Track</dc:title>" +
                                      "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                                      "<res protocolInfo=\"http-get:*:audio/mpeg:*\">" + escapeXml(uri) + "</res>" +
                                      "</item></DIDL-Lite>";
                    mediaTitle = "Unknown Track";
                    mediaArtist = "";
                } else {
                    currentMetaData = ""; // Не храним метаданные для избежания ошибок парсинга
                    // Если метаданные не удалось извлечь, отправляем дату, время и имя файла
                    String fileName = getUriFilename(uri);
                    String currentTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
                    mediaTitle = currentTime + " - " + fileName;
                    mediaArtist = "";
                }

                Log.i(TAG, "Set AV Transport URI: " + uri + ", Title: " + mediaTitle + ", Artist: " + mediaArtist);
                notifyAvTransportChange();
                return buildSoapSuccess("SetAVTransportURI");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting AV transport URI", e);
        }
        return buildSoapError(500, "Internal Server Error");
    }

    // Метод для извлечения метаданных из DIDL-Lite
    private void parseMetadataFromDIDL(String didl) {
        try {
            // Сброс значений по умолчанию
            mediaTitle = "";
            mediaArtist = "";
            mediaContentType = "audio/mpeg";
            
            // Пытаемся извлечь заголовок
            String titleTag = "<dc:title>";
            int titleStart = didl.indexOf(titleTag);
            if (titleStart != -1) {
                titleStart += titleTag.length();
                int titleEnd = didl.indexOf("</dc:title>", titleStart);
                if (titleEnd != -1) {
                    mediaTitle = didl.substring(titleStart, titleEnd).trim();
                }
            }
            
            // Пытаемся извлечь исполнителя
            String artistTag = "<dc:creator>";
            int artistStart = didl.indexOf(artistTag);
            if (artistStart != -1) {
                artistStart += artistTag.length();
                int artistEnd = didl.indexOf("</dc:creator>", artistStart);
                if (artistEnd != -1) {
                    mediaArtist = didl.substring(artistStart, artistEnd).trim();
                }
            }
            
            // Пытаемся извлечить тип контента из protocolInfo
            String protocolInfoTag = "protocolInfo=\"";
            int protocolStart = didl.indexOf(protocolInfoTag);
            if (protocolStart != -1) {
                protocolStart += protocolInfoTag.length();
                int protocolEnd = didl.indexOf("\"", protocolStart);
                if (protocolEnd != -1) {
                    String protocolInfo = didl.substring(protocolStart, protocolEnd);
                    // Формат: http-get:*:audio/mpeg:*
                    String[] parts = protocolInfo.split(":");
                    if (parts.length >= 3) {
                        mediaContentType = parts[2];
                    }
                }
            }
            
            Log.i(TAG, "Parsed metadata - Title: " + mediaTitle + ", Artist: " + mediaArtist + ", ContentType: " + mediaContentType);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing metadata from DIDL", e);
        }
    }

    private String handlePlay(String soapBody) {
        try {
            playMedia();
            Log.i(TAG, "Play command executed");
            return buildSoapSuccess("Play");
        } catch (Exception e) {
            Log.e(TAG, "Error executing play command", e);
            return buildSoapError(500, "Internal Server Error");
        }
    }

    private String handlePause(String soapBody) {
        try {
            pauseMedia();
            Log.i(TAG, "Pause command executed, state now: " + transportState);
            String resp = buildSoapSuccess("Pause");
            Log.d(TAG, "Pause response sent:\n" + resp.substring(0, Math.min(500, resp.length())));
            return resp;
        } catch (Exception e) {
            Log.e(TAG, "Pause failed", e);
            return buildSoapError(500, "Internal Server Error");
        }
    }

    private String handleStop(String soapBody) {
        try {
            stopMedia();
            Log.i(TAG, "Stop command executed");
            return buildSoapSuccess("Stop");
        } catch (Exception e) {
            Log.e(TAG, "Error executing stop command", e);
            return buildSoapError(500, "Internal Server Error");
        }
    }

    private String handleGetTransportInfo(String soapBody) {
        try {
            Log.i(TAG, "GetTransportInfo called - Current state: " + transportState +
                ", URI: " + currentUri + ", MediaPlayer playing: " + mediaPlayer.isPlaying());

            String responseXml = "<CurrentTransportState>" + transportState + "</CurrentTransportState>" +
                                 "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
                                 "<CurrentSpeed>1</CurrentSpeed>";

            String response = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "  <s:Body>\r\n" +
                "    <u:GetTransportInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\r\n" +
                responseXml +
                "    </u:GetTransportInfoResponse>\r\n" +
                "  </s:Body>\r\n" +
                "</s:Envelope>\r\n";

            return buildHttpResponse(200, "text/xml", response);
        } catch (Exception e) {
            Log.e(TAG, "Error getting transport info", e);
            return buildSoapError(500, "Internal Server Error");
        }
    }

    private String handleGetPositionInfo(String soapBody) {
        try {
            String track = "1";
            String trackDuration = formatTime(mediaDurationMs / 1000);
            String trackMetaData = currentMetaData;
            if (trackMetaData == null || trackMetaData.trim().isEmpty() || !trackMetaData.trim().startsWith("<DIDL-Lite")) {
                trackMetaData = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL/\" " +
                               "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                               "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                               "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                               "<dc:title>Unknown Track</dc:title>" +
                               "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                               "</item></DIDL-Lite>";
            }
            trackMetaData = escapeXml(trackMetaData);
            String trackURI = currentUri != null ? escapeXml(currentUri) : "";
            int positionMs = 0;
            if ("PLAYING".equals(transportState) || "PAUSED_PLAYBACK".equals(transportState)) {
                positionMs = mediaPlayer.getCurrentPosition();
            }
            String relTime = formatTime(positionMs / 1000);
            String absTime = relTime; // Absolute time same as relative for simplicity
            String relCount = "2147483647"; // NOT_IMPLEMENTED equivalent
            String absCount = "2147483647"; // NOT_IMPLEMENTED equivalent

            Log.i(TAG, "Get position info for URI: " + trackURI + ", Duration: " + trackDuration + ", Position: " + relTime);

            String responseXml = "<Track>" + track + "</Track>" +
                                 "<TrackDuration>" + trackDuration + "</TrackDuration>" +
                                 "<TrackMetaData>" + trackMetaData + "</TrackMetaData>" +
                                 "<TrackURI>" + trackURI + "</TrackURI>" +
                                 "<RelTime>" + relTime + "</RelTime>" +
                                 "<AbsTime>" + absTime + "</AbsTime>" +
                                 "<RelCount>" + relCount + "</RelCount>" +
                                 "<AbsCount>" + absCount + "</AbsCount>";

            String response = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "  <s:Body>\r\n" +
                "    <u:GetPositionInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\r\n" +
                responseXml +
                "    </u:GetPositionInfoResponse>\r\n" +
                "  </s:Body>\r\n" +
                "</s:Envelope>\r\n";

            return buildHttpResponse(200, "text/xml", response);
        } catch (Exception e) {
            Log.e(TAG, "Error getting position info", e);
            return buildSoapError(500, "Internal Server Error");
        }
    }

    private String handleSetVolume(String soapBody) {
        try {
            String volumeStr = extractXmlValue(soapBody, "DesiredVolume");
            if (volumeStr != null) {
                int volume = Integer.parseInt(volumeStr);
                setMediaVolume(volume);
                Log.i(TAG, "Set volume to: " + volume);
                return buildSoapSuccess("SetVolume");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting volume", e);
        }
        return buildSoapError(500, "Internal Server Error");
    }

    private String handleGetVolume(String soapBody) {
        try {
            int volume = getMediaVolume();
            Log.i(TAG, "Get volume: " + volume);
            String responseXml = "<CurrentVolume>" + volume + "</CurrentVolume>";
            String response = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "  <s:Body>\r\n" +
                "    <u:GetVolumeResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">\r\n" +
                responseXml +
                "    </u:GetVolumeResponse>\r\n" +
                "  </s:Body>\r\n" +
                "</s:Envelope>\r\n";

            return buildHttpResponse(200, "text/xml", response);
        } catch (Exception e) {
            Log.e(TAG, "Error getting volume", e);
            return buildSoapError(500, "Internal Server Error");
        }
    }

    private String handleSeek(String soapBody) {
        try {
            String unit = extractXmlValue(soapBody, "Unit");
            String target = extractXmlValue(soapBody, "Target");

            if ("REL_TIME".equals(unit) && target != null) {
                // target в формате HH:MM:SS или HH:MM:SS.FRACTION
                int seconds = parseRelTime(target);
                if (seconds >= 0) {
                    int positionMs = seconds * 1000;
                    if (positionMs <= mediaDurationMs || mediaDurationMs == 0) {
                        handler.post(() -> {
                            if (mediaPlayer != null) {
                                mediaPlayer.seekTo(positionMs);
                            }
                        });
                        Log.i(TAG, "Seek to: " + seconds + " sec (" + positionMs + " ms)");
                        notifyAvTransportChange(); // обновим позицию в событиях
                        return buildSoapSuccess("Seek");
                    }
                }
            }
            // Если не поддерживается — ошибка
            return buildSoapError(706, "Not Implemented");
        } catch (Exception e) {
            Log.e(TAG, "Error seeking", e);
            return buildSoapError(500, "Internal Server Error");
        }
    }

    private String handleNext(String soapBody) {
        // Поскольку плейлистов нет — возвращаем ошибку, но действие объявлено
        Log.i(TAG, "Next requested - not supported");
        return buildSoapError(701, "Transition not available");
    }

    private String handlePrevious(String soapBody) {
        Log.i(TAG, "Previous requested - not supported");
        return buildSoapError(701, "Transition not available");
    }

    private int parseRelTime(String relTime) {
        // Простой парсер HH:MM:SS или MM:SS
        try {
            String[] parts = relTime.split(":");
            int hours = 0, minutes = 0, seconds = 0;
            if (parts.length == 3) {
                hours = Integer.parseInt(parts[0]);
                minutes = Integer.parseInt(parts[1]);
                seconds = (int) Float.parseFloat(parts[2]);
            } else if (parts.length == 2) {
                minutes = Integer.parseInt(parts[0]);
                seconds = (int) Float.parseFloat(parts[1]);
            } else {
                seconds = Integer.parseInt(parts[0]);
            }
            return hours * 3600 + minutes * 60 + seconds;
        } catch (Exception e) {
            return -1;
        }
    }

    private String extractSoapAction(String request) {
        return extractHeaderValue(request, "SOAPACTION");
    }

    private String extractSoapBody(String request) {
        int bodyStart = request.indexOf("<?xml");
        if (bodyStart != -1) {
            return request.substring(bodyStart);
        }
        return "";
    }

    private String extractXmlValue(String xml, String tagName) {
        String startTag = "<" + tagName + ">";
        String endTag = "</" + tagName + ">";

        int startIndex = xml.indexOf(startTag);
        if (startIndex != -1) {
            startIndex += startTag.length();
            int endIndex = xml.indexOf(endTag, startIndex);
            if (endIndex != -1) {
                return xml.substring(startIndex, endIndex);
            }
        }
        return null;
    }

    private String buildHttpResponse(int statusCode, String contentType, String body) {
        String statusText = statusCode == 200 ? "OK" : "Not Found";
        return "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
               "Content-Type: " + contentType + "\r\n" +
               "Content-Length: " + body.length() + "\r\n" +
               "Connection: close\r\n" +
               "Server: Android/UPnP/1.0 WebLauncher/1.0\r\n" +
               "\r\n" +
               body;
    }

    private String buildSoapSuccess(String actionName) {
        String serviceName = actionName.contains("Volume") ? "RenderingControl" : "AVTransport";

        String resultXml = "<InstanceID>0</InstanceID>";

        if (actionName.equals("GetVolume")) {
            resultXml += "<CurrentVolume>" + volume + "</CurrentVolume>";
        } else if (actionName.equals("Play") || actionName.equals("Pause") || actionName.equals("Stop")) {
            // Можно добавить <Speed>1</Speed> для Play, но не обязательно
        }

        String response = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
            "  <s:Body>\r\n" +
            "    <u:" + actionName + "Response xmlns:u=\"urn:schemas-upnp-org:service:" + serviceName + ":1\">\r\n" +
            resultXml +
            "    </u:" + actionName + "Response>\r\n" +
            "  </s:Body>\r\n" +
            "</s:Envelope>\r\n";

        return buildHttpResponse(200, "text/xml; charset=\"utf-8\"", response);
    }

    private String buildSoapError(int errorCode, String errorDescription) {
        String response = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
            "  <s:Body>\r\n" +
            "    <s:Fault>\r\n" +
            "      <faultcode>s:Client</faultcode>\r\n" +
            "      <faultstring>UPnPError</faultstring>\r\n" +
            "      <detail>\r\n" +
            "        <UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">\r\n" +
            "          <errorCode>" + errorCode + "</errorCode>\r\n" +
            "          <errorDescription>" + errorDescription + "</errorDescription>\r\n" +
            "        </UPnPError>\r\n" +
            "      </detail>\r\n" +
            "    </s:Fault>\r\n" +
            "  </s:Body>\r\n" +
            "</s:Envelope>\r\n";

        return buildHttpResponse(500, "text/xml", response);
    }

    private String buildAVTransportSCPD() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
            "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\r\n" +
            "  <specVersion>\r\n" +
            "    <major>1</major>\r\n" +
            "    <minor>0</minor>\r\n" +
            "  </specVersion>\r\n" +
            "  <actionList>\r\n" +
            "    <action>\r\n" +
            "      <name>SetAVTransportURI</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>CurrentURI</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>AVTransportURI</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>CurrentURIMetaData</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>AVTransportURIMetaData</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "    <action>\r\n" +
            "      <name>Play</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>Speed</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>TransportPlaySpeed</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "    <action>\r\n" +
            "      <name>Pause</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "    <action>\r\n" +
            "      <name>Stop</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "    <action>\r\n" +
            "      <name>GetTransportInfo</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>CurrentTransportState</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>TransportState</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>CurrentTransportStatus</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>TransportStatus</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>CurrentSpeed</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>TransportPlaySpeed</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "    <action>\r\n" +
            "      <name>Seek</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>Unit</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>Target</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "    <action>\r\n" +
            "      <name>Next</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "    <action>\r\n" +
            "      <name>Previous</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "    <action>\r\n" +
            "      <name>GetPositionInfo</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>Track</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>CurrentTrack</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>TrackDuration</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>CurrentTrackDuration</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>TrackMetaData</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>CurrentTrackMetaData</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>TrackURI</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>CurrentTrackURI</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>RelTime</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>RelativeTimePosition</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>AbsTime</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>AbsoluteTimePosition</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>RelCount</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>RelativeCounterPosition</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>AbsCount</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "  </actionList>\r\n" +
            "  <serviceStateTable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>A_ARG_TYPE_InstanceID</name>\r\n" +
            "      <dataType>ui4</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>AVTransportURI</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>AVTransportURIMetaData</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>TransportPlaySpeed</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "      <allowedValueList>\r\n" +
            "        <allowedValue>1</allowedValue>\r\n" +
            "      </allowedValueList>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"yes\">\r\n" +
            "      <name>TransportState</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "      <allowedValueList>\r\n" +
            "        <allowedValue>STOPPED</allowedValue>\r\n" +
            "        <allowedValue>PLAYING</allowedValue>\r\n" +
            "        <allowedValue>PAUSED_PLAYBACK</allowedValue>\r\n" +
            "        <allowedValue>TRANSITIONING</allowedValue>\r\n" +
            "      </allowedValueList>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>TransportStatus</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "      <allowedValueList>\r\n" +
            "        <allowedValue>OK</allowedValue>\r\n" +
            "        <allowedValue>ERROR_OCCURRED</allowedValue>\r\n" +
            "      </allowedValueList>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>CurrentTrack</name>\r\n" +
            "      <dataType>ui4</dataType>\r\n" +
            "      <allowedValueRange>\r\n" +
            "        <minimum>0</minimum>\r\n" +
            "        <maximum>1</maximum>\r\n" +
            "      </allowedValueRange>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>CurrentTrackDuration</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>CurrentTrackMetaData</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>CurrentTrackURI</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>RelativeTimePosition</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>AbsoluteTimePosition</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>RelativeCounterPosition</name>\r\n" +
            "      <dataType>i4</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>AbsoluteCounterPosition</name>\r\n" +
            "      <dataType>i4</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"yes\">\r\n" +
            "      <name>LastChange</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>A_ARG_TYPE_SeekMode</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "      <allowedValueList>\r\n" +
            "        <allowedValue>REL_TIME</allowedValue>\r\n" +
            "        <allowedValue>TRACK_NR</allowedValue>\r\n" +
            "      </allowedValueList>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>A_ARG_TYPE_SeekTarget</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "  </serviceStateTable>\r\n" +
            "</scpd>\r\n";

        return buildHttpResponse(200, "text/xml", xml);
    }

    private String buildRenderingControlSCPD() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
            "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\r\n" +
            "  <specVersion>\r\n" +
            "    <major>1</major>\r\n" +
            "    <minor>0</minor>\r\n" +
            "  </specVersion>\r\n" +
            "  <actionList>\r\n" +
            "    <action>\r\n" +
            "      <name>SetVolume</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>Channel</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>DesiredVolume</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>Volume</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "    <action>\r\n" +
            "      <name>GetVolume</name>\r\n" +
            "      <argumentList>\r\n" +
            "        <argument>\r\n" +
            "          <name>InstanceID</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>Channel</name>\r\n" +
            "          <direction>in</direction>\r\n" +
            "          <relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "        <argument>\r\n" +
            "          <name>CurrentVolume</name>\r\n" +
            "          <direction>out</direction>\r\n" +
            "          <relatedStateVariable>Volume</relatedStateVariable>\r\n" +
            "        </argument>\r\n" +
            "      </argumentList>\r\n" +
            "    </action>\r\n" +
            "  </actionList>\r\n" +
            "  <serviceStateTable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>A_ARG_TYPE_InstanceID</name>\r\n" +
            "      <dataType>ui4</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"no\">\r\n" +
            "      <name>A_ARG_TYPE_Channel</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "      <allowedValueList>\r\n" +
            "        <allowedValue>Master</allowedValue>\r\n" +
            "      </allowedValueList>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"yes\">\r\n" +
            "      <name>Volume</name>\r\n" +
            "      <dataType>ui2</dataType>\r\n" +
            "      <allowedValueRange>\r\n" +
            "        <minimum>0</minimum>\r\n" +
            "        <maximum>100</maximum>\r\n" +
            "        <step>1</step>\r\n" +
            "      </allowedValueRange>\r\n" +
            "    </stateVariable>\r\n" +
            "    <stateVariable sendEvents=\"yes\">\r\n" +
            "      <name>LastChange</name>\r\n" +
            "      <dataType>string</dataType>\r\n" +
            "    </stateVariable>\r\n" +
            "  </serviceStateTable>\r\n" +
            "</scpd>\r\n";

        return buildHttpResponse(200, "text/xml", xml);
    }

    private String handleEventSubscription(String request, List<String> callbacks, String sidSuffix) {
        Log.i(TAG, "Event subscription request received for " + sidSuffix);

        // Extract callback URL and timeout
        String callback = extractHeaderValue(request, "CALLBACK");
        String timeout = extractHeaderValue(request, "TIMEOUT");

        if (callback != null) {
            // Clean up callback URL (remove < > brackets if present)
            callback = callback.replaceAll("^<|>$", "");

            Log.i(TAG, "Event subscription - Callback: " + callback + ", Timeout: " + timeout);

            // Store the callback URL for event notifications
            if (!callbacks.contains(callback)) {
                callbacks.add(callback);
                Log.i(TAG, "Added event callback for " + sidSuffix + ": " + callback + " (total: " + callbacks.size() + ")");
            }

            // Acknowledge the subscription
            String sid = "uuid:" + deviceUUID + sidSuffix;
            String response = "HTTP/1.1 200 OK\r\n" +
                "DATE: " + new Date().toString() + "\r\n" +
                "SERVER: Android/UPnP/1.0 WebLauncher/1.0\r\n" +
                "SID: " + sid + "\r\n" +
                "TIMEOUT: Second-1800\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";

            // Send initial event notification with current state
            if (sidSuffix.equals("_AVTransport")) {
                notifyAvTransportChange();
            } else if (sidSuffix.equals("_RenderingControl")) {
                notifyRenderingControlChange();
            }

            return response;
        } else {
            Log.w(TAG, "Event subscription missing CALLBACK header");
            return "HTTP/1.1 400 Bad Request\r\n\r\n";
        }
    }

    private void notifyAvTransportChange() {
        Log.i(TAG, "Notifying AVTransport state change to " + avTransportCallbacks.size() + " callbacks");

        String sid = "uuid:" + deviceUUID + "_AVTransport";
        for (String callback : avTransportCallbacks) {
            sendEventNotification(callback, sid, buildAvTransportLastChange());
        }
    }

    private void notifyRenderingControlChange() {
        Log.i(TAG, "Notifying RenderingControl state change to " + renderingControlCallbacks.size() + " callbacks");

        String sid = "uuid:" + deviceUUID + "_RenderingControl";
        for (String callback : renderingControlCallbacks) {
            sendEventNotification(callback, sid, buildRenderingControlLastChange());
        }
    }

    private String buildAvTransportLastChange() {
        String eventXml = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">\n" +
                          "<InstanceID val=\"0\">\n" +
                          "<TransportState val=\"" + transportState + "\"/>\n" +
                          "<TransportStatus val=\"OK\"/>\n" +
                          "<CurrentTrackURI val=\"" + (currentUri != null ? escapeXml(currentUri) : "") + "\"/>\n" +
                          "</InstanceID>\n" +
                          "</Event>";
        return eventXml;
    }

    private String buildRenderingControlLastChange() {
        String eventXml = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\">\n" +
                          "<InstanceID val=\"0\">\n" +
                          "<Volume channel=\"Master\" val=\"" + volume + "\"/>\n" +
                          "</InstanceID>\n" +
                          "</Event>";
        return eventXml;
    }

    private void sendEventNotification(String callbackUrl, String sid, String lastChangeXml) {
        try {
            Log.i(TAG, "Sending event notification to: " + callbackUrl);
        
            String eventXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                              "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">\r\n" +
                              "  <e:property>\r\n" +
                              "    <LastChange>" + escapeXml(lastChangeXml) + "</LastChange>\r\n" +
                              "  </e:property>\r\n" +
                              "</e:propertyset>\r\n";
        
            new Thread(() -> {
                try {
                    java.net.URL url = new java.net.URL(callbackUrl);
                    String host = url.getHost();
                    int port = url.getPort() != -1 ? url.getPort() : 80;
                    String path = url.getPath().isEmpty() ? "/" : url.getPath();  // на всякий случай
                
                    try (Socket socket = new Socket(host, port);
                         java.io.PrintWriter out = new java.io.PrintWriter(socket.getOutputStream(), true);
                         java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()))) {
                        
                        out.print("NOTIFY " + path + " HTTP/1.1\r\n");
                        out.print("HOST: " + host + ":" + port + "\r\n");
                        out.print("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n");
                        out.print("NT: upnp:event\r\n");
                        out.print("NTS: upnp:propchange\r\n");
                        out.print("SID: " + sid + "\r\n");
                        out.print("SEQ: 0\r\n");
                        out.print("CONTENT-LENGTH: " + eventXml.getBytes("UTF-8").length + "\r\n");
                        out.print("\r\n");  // пустая строка — разделитель заголовков и тела
                        out.print(eventXml);
                        out.flush();
                        
                        String responseLine = in.readLine();
                        if (responseLine != null) {
                            Log.i(TAG, "Event notification response: " + responseLine);
                        } else {
                            Log.w(TAG, "No response from event callback");
                        }
                    
                        Log.i(TAG, "Event notification sent successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send event notification", e);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in event notification thread", e);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error preparing event notification", e);
        }
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String formatTime(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}
