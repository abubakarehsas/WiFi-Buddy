package edu.rit.se.wifibuddy;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

// TODO: Add JavaDoc
public class WifiDirectHandler extends NonStopIntentService implements
        WifiP2pManager.ConnectionInfoListener,
        Handler.Callback {

    private static final String ANDROID_SERVICE_NAME = "Wi-Fi Buddy";
    public static final String TAG = "wfd_";
    private final IBinder binder = new WifiTesterBinder();

    public static final String SERVICE_MAP_KEY = "serviceMapKey";
    public static final String TXT_MAP_KEY = "txtMapKey";
    public static final String MESSAGE_KEY = "messageKey";
    private final String PEERS = "peers";

    private Map<String, DnsSdTxtRecord> dnsSdTxtRecordMap;
    private Map<String, DnsSdService> dnsSdServiceMap;
    private List<ServiceDiscoveryTask> serviceDiscoveryTasks;
    private LocalBroadcastManager localBroadcastManager;
    private BroadcastReceiver p2pBroadcastReceiver;
    private BroadcastReceiver wifiBroadcastReceiver;
    private WifiP2pServiceInfo wifiP2pServiceInfo;
    private WifiP2pServiceRequest serviceRequest;
    private Handler handler = new Handler((Handler.Callback) this);
    private Thread socketHandler;
    private CommunicationManager communicationManager = null;
    public static final int MESSAGE_READ = 0x400 + 1;
    public static final int MY_HANDLE = 0x400 + 2;
    public static final int COMMUNICATION_DISCONNECTED = 0x400 + 3;
    public static final int SERVER_PORT = 4545;

    private boolean isDiscovering = false;
    private boolean isGroupOwner = false;
    private boolean groupFormed = false;
    private boolean addLocalService=true;
    public static int serviceStatus=0;
    private boolean isNoPromptNetworkConnected = false;
    private boolean serviceDiscoveryRegistered = false;
    private boolean stopDiscoveryAfterGroupFormed = false;

    // Flag for creating a no prompt service
    private int noPromptServiceStatus;
    private ServiceData noPromptServiceData;
    private WifiP2pManager.ActionListener noPromptActionListener;
    private String noPromptNetworkSsidExpected=null;
    /**
     * Extra boolean that is broadcasted when the result of an attempt to connect to the no prompt
     * network is known
     */
    public static final String EXTRA_NOPROMPT_NETWORK_SUCCEEDED="isNoPromptConnected";
    public static final String EXTRA_WIFIDIRECT_CONNECTION_SUCCEEDED="isNormalWiFiDirectConnected";

    public static final String EXTRA_CONNECT_TO_NORMAL_WIFI_DIRECT_MAC_ADDR = "normalWifiDirectMacAddr";

    public static final String EXTRA_CANCEL_CONNECT_TO_NORMAL_WIFI_DIRECT_SUCCEEDED = "isCancelled";

    // Variables created in onCreate()
    private WifiP2pManager.Channel channel;
    private WifiP2pManager wifiP2pManager;
    private WifiManager wifiManager;

    private WifiP2pDevice thisDevice;
    private WifiP2pGroup wifiP2pGroup;
    private WifiP2pInfo wifiP2pInfo;
    private List<ScanResult> wifiScanResults;


    //handle running peer discovery on an interval. Also required to rebroadcast any local service
    private int peerDiscoveryInterval =30000;
    private Handler peerDiscoveryHandler =new Handler();

    /**
     * A no prompt network was created and it has been detected that the network has timed out and
     * needs to be recreated
     */
    public static final int NOPROMPT_STATUS_TIMEDOUT = -1;

    /**
     * The no prompt network service is not active
     */
    public static final int NOPROMPT_STATUS_INACTIVE = 0;

    /**
     * No prompt network service is starting and we have requested the WifiP2PManager to make a group
     */
    public static final int NOPROMPT_STATUS_GROUP_REQUESTED = 1;

    /**
     * No prompt network service is starting and the group has been created
     */
    public static final int NOPROMPT_STATUS_GROUP_CREATED = 2;

    /**
     * No prompt network service is starting and we are now adding the local service so others can
     * discover it
     */
    public static final int NOPROMPT_STATUS_ADDING_LOCAL_SERVICE = 3;

    /**
     * The no prompt network service is active the group is created and a local service has been
     * broadcasted
     */
    public static final int NOPROMPT_STATUS_ACTIVE = 4;

    /**
     * The service has been created successfully
     */
    public static final int NORMAL_SERVICE_STATUS_ACTIVE = 5;

    /**
     * We attempted to create a no prompt network service but adding a group failed
     */
    public static final int NOPROMPT_STATUS_FAILED_ADDING_GROUP = 20;

    public static final int NOPROMPT_STATUS_FAILED_ADDING_LOCAL_SERVICE = 21;

    private boolean isDiscoveringPeers = false;

    private Object peerDiscoveryLock = new Object();

    private Runnable postedPeerDiscoveryRunnable = null;

    private WifiP2pDeviceList wifiP2pDeviceList;

    private Object wifiP2pDeviceLock = new Object();

    private WifiDirectAutoAccept autoAccept;

    private boolean localServicePeerDiscoveryKickEnabled = true;

    private ActionListenerTaskQueue taskQueue;


    /**
     * Peer discovery runnable. This serves two purposes. It can be used to discover peers, if
     * required.
     *
     * Secondly adding a local service requires periodic UDP broadcasts for other devices to see it.
     * Calling discoverPeers is apparently needed to force rebroadcasting the service so it can be
     * reliably discovered.
     *
     * see: http://stackoverflow.com/questions/26300889/wifi-p2p-service-discovery-works-intermittently
     */
    private Runnable peerDiscoveryRunnable =new Runnable() {

        WifiP2pManager.ActionListener peerDiscoveryActionListener;

        @Override
        public void run() {
            synchronized (peerDiscoveryLock) {
                boolean peerDiscoveryRequired = isPeerDiscoveryRequired();
                postedPeerDiscoveryRunnable = null;

                if(wifiP2pManager!= null && peerDiscoveryRequired){
                    Log.d(TAG,"Service rebroadcast kick - requesting");
                    wifiP2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "Service rebroadcast kick - onSuccess");
                        }

                        @Override
                        public void onFailure(int reason) {
                            Log.i(TAG, "Service rebroadcast kick - onFailure: " +
                                    FailureReason.fromInteger(reason));
                        }
                    });
                }else {
                    Log.w(TAG, "discoverPeersRunnable: wifiP2pManager is null or discovery not required");
                }

                if(peerDiscoveryRequired){
                    postedPeerDiscoveryRunnable = peerDiscoveryRunnable;
                    peerDiscoveryHandler.postDelayed(
                            peerDiscoveryRunnable, peerDiscoveryInterval);
                }else {
                    Log.i(TAG, "discoverPeersRunnable: peer discovery no longer required.");
                }
            }

        }
    };


    /** Constructor **/
    public WifiDirectHandler() {
        super(ANDROID_SERVICE_NAME);
        dnsSdTxtRecordMap = new HashMap<>();
        dnsSdServiceMap = new HashMap<>();
        taskQueue = new ActionListenerTaskQueue("WifiDirectHandler - queue");
    }

    /**
     * Get the current status of the no prompt network
     *
     * @return Status as an int corresponding with NOPROMPT_STATUS flags
     */
    public synchronized int getNoPromptServiceStatus() {
        return noPromptServiceStatus;
    }

    /**
     * Set the current status of the no prompt network : used for purposes of thread safety
     * @param noPromptServiceStatus
     */
    private synchronized void setNoPromptServiceStatus(int noPromptServiceStatus) {
        this.noPromptServiceStatus = noPromptServiceStatus;
    }

    /**
     * Registers the Wi-Fi manager, registers the app with the Wi-Fi P2P framework, registers the
     * P2P BroadcastReceiver, and registers a local BroadcastManager
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Creating WifiDirectHandler");

        // Registers the Wi-Fi Manager and the Wi-Fi BroadcastReceiver
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if(isWifiEnabled()){
            registerWifiReceiver();

            // Scans for available Wi-Fi networks
            wifiManager.startScan();

            if (wifiManager.isWifiEnabled()) {
                Log.i(TAG, "Wi-Fi enabled on load");
            } else {
                Log.i(TAG, "Wi-Fi disabled on load");
            }

            // Registers a local BroadcastManager that is used to broadcast Intents to Activities
            localBroadcastManager = LocalBroadcastManager.getInstance(this);
            Log.i(TAG, "WifiDirectHandler created");
        }else{
            Log.e(TAG,"WifiDirectHandler: onCreate: WiFi is disabled");
        }
    }

    /**
     * Registers the application with the Wi-Fi P2P framework
     * Initializes the P2P manager and gets a P2P communication channel
     */
    public void registerP2p() {
        // Manages Wi-Fi P2P connectivity
        wifiP2pManager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);

        // initialize() registers the app with the Wi-Fi P2P framework
        // Channel is used to communicate with the Wi-Fi P2P framework
        // Main Looper is the Looper for the main thread of the current process
        channel = wifiP2pManager.initialize(this, getMainLooper(), null);
        Log.i(TAG, "Registered with Wi-Fi P2P framework");
    }

    /**
     * Unregisters the application with the Wi-Fi P2P framework
     */
    public void unregisterP2p() {
        if (wifiP2pManager != null) {
            wifiP2pManager = null;
            channel = null;
            thisDevice = null;
            localBroadcastManager.sendBroadcast(new Intent(Action.DEVICE_CHANGED));
            Log.i(TAG, "Unregistered with Wi-Fi P2P framework");
        }else{
            Log.e(TAG,"WifiDirectHandler: unregisterP2p: wifip2pManager is null");
        }
    }

    /**
     * Registers a WifiDirectBroadcastReceiver with an IntentFilter listening for P2P Actions
     */
    public void registerP2pReceiver() {
        p2pBroadcastReceiver = new WifiDirectBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();

        // Indicates a change in the list of available peers
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        // Indicates a change in the Wi-Fi P2P status
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        // Indicates the state of Wi-Fi P2P connectivity has changed
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        // Indicates this device's details have changed.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        registerReceiver(p2pBroadcastReceiver, intentFilter);
        Log.i(TAG, "P2P BroadcastReceiver registered");
    }


    /**
     * Unregisters the WifiDirectBroadcastReceiver and IntentFilter
     */
    public void unregisterP2pReceiver() {
        if (p2pBroadcastReceiver != null) {
            unregisterReceiver(p2pBroadcastReceiver);
            p2pBroadcastReceiver = null;
            Log.i(TAG, "P2P BroadcastReceiver unregistered");
        }
    }

    public void registerWifiReceiver() {
        wifiBroadcastReceiver = new WifiBroadcastReceiver();
        IntentFilter wifiIntentFilter = new IntentFilter();
        // Indicates that Wi-Fi has been enabled, disabled, enabling, disabling, or unknown
        wifiIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        wifiIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        wifiIntentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(wifiBroadcastReceiver, wifiIntentFilter);
        Log.i(TAG, "Wi-Fi BroadcastReceiver registered");
    }

    public void unregisterWifiReceiver() {
        if (wifiBroadcastReceiver != null) {
            unregisterReceiver(wifiBroadcastReceiver);
            wifiBroadcastReceiver = null;
            Log.i(TAG, "Wi-Fi BroadcastReceiver unregistered");
        }
    }

    public void unregisterWifi() {
        if (wifiManager != null) {
            wifiManager = null;
            Log.i(TAG, "Wi-Fi manager unregistered");
        }else{
            Log.e(TAG,"WifiDirectHandler: unregisterWifi: wifiManager is null");
        }
    }

    /**
     * The requested connection info is available
     * @param wifiP2pInfo Wi-Fi P2P connection info
     */
    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo) {
        Log.i(TAG, "Connection info available");

        Log.i(TAG, "WifiP2pInfo: ");
        Log.i(TAG, p2pInfoToString(wifiP2pInfo));
        this.groupFormed = wifiP2pInfo.groupFormed;
        this.isGroupOwner = wifiP2pInfo.isGroupOwner;
        this.wifiP2pInfo=wifiP2pInfo;

        if (wifiP2pInfo.groupFormed) {
            if(stopDiscoveryAfterGroupFormed){
                stopServiceDiscovery();
            }

            if (wifiP2pInfo.isGroupOwner && socketHandler == null) {
                Log.i(TAG, "Connected as group owner");
                try {
                    socketHandler = new OwnerSocketHandler(this.getHandler());
                    socketHandler.start();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create a server thread - " + e.getMessage());
                    return;
                }
            } else {
                Log.i(TAG, "Connected as peer");
                socketHandler = new ClientSocketHandler(this.getHandler(), wifiP2pInfo.groupOwnerAddress);
                socketHandler.start();
            }



        } else {

            Log.w(TAG, "Group not formed");
        }

        //TODO: Add extras to this broadcast
        localBroadcastManager.sendBroadcast(new Intent(Action.CONNECTION_INFO_AVAILABLE));
    }

    // TODO add JavaDoc
    public void addLocalService(final String serviceName, HashMap<String, String> serviceRecord, @Nullable final WifiP2pManager.ActionListener actionListener) {

        if(wifiP2pManager!=null){
            // Logs information about local service
            Log.i(TAG, "Adding local service: " + serviceName + " : clearing");
            // Service information
            wifiP2pServiceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                    serviceName,
                    ServiceType.PRESENCE_TCP.toString(),
                    serviceRecord
            );

            final WifiP2pServiceInfo serviceInfo = wifiP2pServiceInfo;

            taskQueue.queueTask(new ActionListenerTask("addLocalService - clearLocalServices") {
                @Override
                public void run(WifiP2pManager.ActionListener listener) {
                    wifiP2pManager.clearLocalServices(channel, listener);
                }
            })
            //TODO: Update queue to ensure this should run only if clearLocalServices succeeds
            .queueTask(new ActionListenerTask("addLocalService - addLocalService") {
                @Override
                public void run(WifiP2pManager.ActionListener listener) {
                    Log.i(TAG, "Adding local service: " + serviceName + " : adding");
                    wifiP2pManager.addLocalService(channel, serviceInfo, listener);
                }

                @Override
                public void onSuccess() {
                    super.onSuccess();
                    Log.i(TAG, "Local service added");
                    actionListenerSuccess(actionListener);
                    serviceStatus=NORMAL_SERVICE_STATUS_ACTIVE;
                    wifiP2pServiceInfo = serviceInfo;
                    localBroadcastManager.sendBroadcast(new Intent(Action.NOPROMPT_SERVICE_CREATED_ACTION));
                    discoverPeers(peerDiscoveryInterval);//call discoverPeers to make sure broadcasting gets kicked
                }

                @Override
                public void onFailure(int reason) {
                    super.onFailure(reason);
                    wifiP2pServiceInfo = null;
                    actionListenerFailure(actionListener, reason);
                }
            });

        }else{
            Log.e(TAG,"WifiDirectHandler: addLocalService: wifip2pManager is null");
        }
    }

    public void addLocalService(String serviceName, HashMap<String, String> serviceRecord) {
        addLocalService(serviceName, serviceRecord, null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopServiceDiscovery();
        removeGroup();
        removePersistentGroups();
        removeService();
        unregisterP2pReceiver();
        unregisterP2p();
        unregisterWifiReceiver();
        unregisterWifi();
        Log.i(TAG, "Wifi Handler service destroyed");
    }

    /**
     * Removes persistent/remembered groups
     *
     * Source: https://android.googlesource.com/platform/cts/+/jb-mr1-dev%5E1%5E2..jb-mr1-dev%5E1/
     * Author: Nick  Kralevich <nnk@google.com>
     *
     * WifiP2pManager.java has a method deletePersistentGroup(), but it is not accessible in the
     * SDK. According to Vinit Deshpande <vinitd@google.com>, it is a common Android paradigm to
     * expose certain APIs in the SDK and hide others. This allows Android to maintain stability and
     * security. As a workaround, this removePersistentGroups() method uses Java reflection to call
     * the hidden method. We can list all the methods in WifiP2pManager and invoke "deletePersistentGroup"
     * if it exists. This is used to remove all possible persistent/remembered groups.
     */
    public void removePersistentGroups() {
        if(wifiP2pManager!=null){
            try {
                Method[] methods = WifiP2pManager.class.getMethods();
                for (int i = 0; i < methods.length; i++) {
                    if (methods[i].getName().equals("deletePersistentGroup")) {
                        // Remove any persistent group
                        for (int netid = 0; netid < 32; netid++) {
                            methods[i].invoke(wifiP2pManager, channel, netid, null);
                        }
                    }
                }
                Log.i(TAG, "Persistent groups removed");
            } catch(Exception e) {
                Log.e(TAG, "Failure removing persistent groups: " + e.getMessage());
                e.printStackTrace();
            }
        }else{
            Log.e(TAG,"WifiDirectHandler: removePersistentGroups: wifip2pManager is null");
        }
    }

    /**
     * Set the wifi direct channels to use. This uses a method call which is hidden and invoked
     * via reflection. By default Android listens and operates only on channel 1.
     *
     * See:: source from
     *  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/wifi/java/android/net/wifi/p2p/WifiP2pManager.java
     *
     * @param listeningChannel
     * @param operatingChannel
     */
    public void setWifiDirectChannel(final int listeningChannel, final int operatingChannel) {
        try {
            Method setWifiP2pChannels = wifiP2pManager.getClass().getMethod("setWifiP2pChannels",
                    WifiP2pManager.Channel.class, int.class, int.class,
                    WifiP2pManager.ActionListener.class);
            setWifiP2pChannels.invoke(wifiP2pManager, channel, listeningChannel, operatingChannel,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "Changed channel (" + listeningChannel + "/" + operatingChannel +
                                    ") succeeded");
                        }

                        @Override
                        public void onFailure(int reason) {
                            Log.d(TAG, "Changed channel (" + listeningChannel + "/" +operatingChannel +
                                    ")  failed");
                        }
                    });

        }catch(Exception e) {
            Log.e(TAG, "Exception attempting to invoke change channel to " + listeningChannel
                    + "/" + operatingChannel, e);
        }
    }

    /**
     * Auto accept can automatically accept a connection using a hidden method if the activity
     * running this service is in the foreground. It will not work for services with foreground
     * notifications.
     *
     * @param enabled true to automatically accept incoming group requests, false otherwise
     */
    public void setAutoAccept(boolean enabled) {
        if(autoAccept == null) {
            autoAccept = wifiP2pManager != null ? new WifiDirectAutoAccept(wifiP2pManager, channel)
                    : new WifiDirectAutoAccept(getApplicationContext());
        }

        autoAccept.intercept(enabled);
    }

    /**
     * Removes the current WifiP2pGroup in the WifiP2pChannel.
     */
    public void removeGroup(final @Nullable WifiP2pManager.ActionListener actionListener) {
        if(wifiP2pManager!=null){
            if (wifiP2pGroup != null) {
                wifiP2pManager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        wifiP2pGroup = null;
                        groupFormed = false;
                        isGroupOwner = false;
                        Log.i(TAG, "Group removed");
                        actionListenerSuccess(actionListener);
                    }

                    @Override
                    public void onFailure(int reason) {
                        Log.e(TAG, "Failure removing group: " + FailureReason.fromInteger(reason).toString());
                        actionListenerFailure(actionListener, reason);
                    }
                });
            }else {
                Log.i(TAG, "removeGroup: no group to remove");
                actionListenerSuccess(actionListener);
            }
        }else{
            Log.e(TAG,"WifiDirectHandler: removeGroup: wifip2pManager is null");
            actionListenerFailure(actionListener, WifiP2pManager.ERROR);
        }
    }

    public void removeGroup() {
        removeGroup(null);
    }

    /*
     * Registers listeners for DNS-SD services. These are callbacks invoked
     * by the system when a service is actually discovered.
     */
    private void registerServiceDiscoveryListeners() {
        if(wifiP2pManager!=null){
            // DnsSdTxtRecordListener
            // Interface for callback invocation when Bonjour TXT record is available for a service
            // Used to listen for incoming records and get peer device information
            WifiP2pManager.DnsSdTxtRecordListener txtRecordListener = new WifiP2pManager.DnsSdTxtRecordListener() {
                @Override
                public void onDnsSdTxtRecordAvailable(String fullDomainName, Map<String, String> txtRecordMap, WifiP2pDevice srcDevice) {
                    // Records of peer are available
                    Log.i(TAG, "Peer DNS-SD TXT Record available: " + fullDomainName + " on "
                            + srcDevice.deviceName + "(" + srcDevice.deviceAddress + ")");


                    Intent intent = new Intent(Action.DNS_SD_TXT_RECORD_AVAILABLE);
                    intent.putExtra(TXT_MAP_KEY, srcDevice.deviceAddress);
                    dnsSdTxtRecordMap.put(srcDevice.deviceAddress, new DnsSdTxtRecord(fullDomainName, txtRecordMap, srcDevice));
                    localBroadcastManager.sendBroadcast(intent);
                }
            };

            // DnsSdServiceResponseListener
            // Interface for callback invocation when Bonjour service discovery response is received
            // Used to get service information
            WifiP2pManager.DnsSdServiceResponseListener serviceResponseListener = new WifiP2pManager.DnsSdServiceResponseListener() {
                @Override
                public void onDnsSdServiceAvailable(String instanceName, String registrationType, WifiP2pDevice srcDevice) {
                    // Not sure if we want to track the map here or just send the service in the request to let the caller do
                    // what it wants with it

                    Log.i(TAG, "DNS-SD service available");
                    Log.i(TAG, "Local service found: " + instanceName);
                    Log.i("TAG", "Source device: ");
                    Log.i(TAG, p2pDeviceToString(srcDevice));
                    dnsSdServiceMap.put(srcDevice.deviceAddress, new DnsSdService(instanceName, registrationType, srcDevice));
                    Intent intent = new Intent(Action.DNS_SD_SERVICE_AVAILABLE);
                    intent.putExtra(SERVICE_MAP_KEY, srcDevice.deviceAddress);
                    localBroadcastManager.sendBroadcast(intent);
                }
            };

            wifiP2pManager.setDnsSdResponseListeners(channel, serviceResponseListener, txtRecordListener);
            Log.i(TAG, "Service discovery listeners registered");
        }else{
            Log.e(TAG,"WifiDirectHandler: registerServiceDiscoveryListener: wifip2pManager is null");
        }
    }

    private void addServiceDiscoveryRequest(@Nullable final WifiP2pServiceRequest request,
                                            @Nullable final WifiP2pManager.ActionListener listener) {
        if(wifiP2pManager!=null){
            serviceRequest = request != null ? request : WifiP2pDnsSdServiceRequest.newInstance();

            // Tell the framework we want to scan for services. Prerequisite for discovering services
            wifiP2pManager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    wifiP2pManager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "Service discovery request added");
                            if(listener != null)
                                listener.onSuccess();
                        }

                        @Override
                        public void onFailure(int reason) {
                            Log.e(TAG, "Failure adding service discovery request: " + FailureReason.fromInteger(reason).toString());
                            serviceRequest = null;
                            if(listener != null)
                                listener.onFailure(reason);
                        }
                    });
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failure clearing service requests: " + FailureReason.fromInteger(reason).toString());
                    serviceRequest = null;
                    if(listener != null)
                        listener.onFailure(reason);
                }
            });

        }else{
            Log.e(TAG,"WifiDirectHandler: addServiceDiscoveryRequest: wifip2pManager is null");
        }
    }

    /**
     * Previous behaviour was to stop discovery of services after a group has been formed. However
     * some devices when Wifi P2P is initialized would themselves create a group automatically.
     *
     * This leads to continuouslyDiscoverServices being silently canceled (probably undesired).
     *
     * @param stopDiscoveryAfterGroupFormed true to stop discovery automatically after a group is formed; false otherwise
     */
    public void setStopDiscoveryAfterGroupFormed(boolean stopDiscoveryAfterGroupFormed){
        this.stopDiscoveryAfterGroupFormed=stopDiscoveryAfterGroupFormed;
    }

    /**
     * Set the interval at which peer discovery is called. This effects both continuouslyDiscoverPeers
     * and rebroadcasting any local services.
     *
     * @param peerDiscoveryInterval
     */
    public void setPeerDiscoveryInterval(int peerDiscoveryInterval){
        this.peerDiscoveryInterval = peerDiscoveryInterval;
    }

    /**
     * By default after a group is formed service discovery will be stopped automatically.
     *
     * @return true if discovery will be stopped automatically after group is formed, false otherwise
     */
    public boolean isStopDiscoveryAfterGroupFormed(){
        return  stopDiscoveryAfterGroupFormed;
    }

    /**
     * Initiates a service discovery. This has a 2 minute timeout. To continuously
     * discover services use continuouslyDiscoverServices
     */
    public void discoverServices(){
        if(wifiP2pManager!=null){
            registerServiceDiscoveryListeners();
            // Initiates service discovery. Starts to scan for services we want to connect to
            wifiP2pManager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "Service discovery initiated");
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failure initiating service discovery: " + FailureReason.fromInteger(reason).toString());

                    /*
                     * Very rarely, and for no apparent reason, even if service requests have been
                     * registered we can get a NO_SERVICE_REQUESTS error. In that case the best we
                     * can try to do is to reset service discovery.
                     */
                    if(reason == WifiP2pManager.NO_SERVICE_REQUESTS && isDiscovering) {
                        Log.w(TAG, "Detected failure due to NO_SERVICE_REQUESTS whilst isDiscovering. Resetting service discovery");
                        resetServiceDiscovery();
                    }
                }
            });
        }else{
            Log.e(TAG,"WifiDirectHandler: discoverServices: wifip2pManager is null");
        }
    }

    /**
     * Calls initial services discovery call and submits the first
     * Discover task. This will continue until stopDiscoveringServices is called
     *
     * @param request WifiP2pServiceRequest with details of which service we're looking for.
     *                Specifying this seems to improve discovery performance. Devices with a local
     *                service are supposed to respond to a service request for a service that they
     *                have.
     */
    public void continuouslyDiscoverServices(@Nullable WifiP2pServiceRequest request){
        Log.i(TAG, "Continuously Discover services called");

        if (!serviceDiscoveryRegistered) {
            Log.i(TAG, "Setting up service discovery");
            registerServiceDiscoveryListeners();
            serviceDiscoveryRegistered = true;
        }

        // TODO Change this to give some sort of status
        if (isDiscovering){
            Log.w(TAG, "Services are still discovering, do not need to make this call");
        } else {
            addServiceDiscoveryRequest(request, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    isDiscovering = true;
                    // List to track discovery tasks in progress
                    serviceDiscoveryTasks = new ArrayList<>();
                    // Make discover call and first discover task submission
                    discoverServices();
                    submitServiceDiscoveryTask();
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Error adding service discovery request: " + FailureReason.fromInteger(reason));
                }
            });

        }
    }

    public void continuouslyDiscoverServices() {
        continuouslyDiscoverServices(null);
    }

    public void continuouslyDiscoverPeers() {
        if(!isDiscoveringPeers) {
            Log.i(TAG, "Continuously discover peers: starting");
            isDiscoveringPeers = true;
            discoverPeers();
        }else {
            Log.i(TAG, "Continuously discover peers, already discovering.");
        }
    }

    /**
     * Start discovering peers.
     *
     * @param delay
     */
    public void discoverPeers(int delay) {
        synchronized (peerDiscoveryLock) {
            if(this.postedPeerDiscoveryRunnable == null && isPeerDiscoveryRequired()) {
                postedPeerDiscoveryRunnable = peerDiscoveryRunnable;
                peerDiscoveryHandler.postDelayed(
                        peerDiscoveryRunnable, delay);
                Log.i(TAG, "discoverPeers: posted runnable for peer discovery");
            }else if(!isPeerDiscoveryRequired()){
                Log.i(TAG, "discoverPeers: peer discovery not required");
            }else if(postedPeerDiscoveryRunnable != null) {
                Log.i(TAG, "discoverPeers: post peer discovery runnable not null, no need to post again");
            }
        }
    }

    public void discoverPeers() {
        discoverPeers(100);
    }

    private boolean isPeerDiscoveryRequired() {
        synchronized (peerDiscoveryLock) {
            return isDiscoveringPeers || (wifiP2pServiceInfo != null && localServicePeerDiscoveryKickEnabled);
        }
    }

    public void stopPeerDiscovery() {
        synchronized (peerDiscoveryLock) {
            Log.i(TAG, "stopPeerDiscovery");
            isDiscoveringPeers = false;
        }
    }


    public void stopServiceDiscovery(WifiP2pManager.ActionListener actionListener) {
        Log.i(TAG, "Stopping service discovery");
        if (isDiscovering) {
            dnsSdServiceMap = new HashMap<>();
            dnsSdTxtRecordMap = new HashMap<>();
            // Cancel all discover tasks that may be in progress
            for (ServiceDiscoveryTask serviceDiscoveryTask : serviceDiscoveryTasks) {
                serviceDiscoveryTask.cancel();
            }
            serviceDiscoveryTasks = null;
            isDiscovering = false;
            Log.i(TAG, "Service discovery stopped");
            clearServiceDiscoveryRequests(actionListener);
        }else {
            Log.i(TAG, "Service discovery not running, nothing to stop");
            if(actionListener != null)
                actionListener.onSuccess();
        }
    }

    public void stopServiceDiscovery() {
        stopServiceDiscovery(null);
    }

    public void resetServiceDiscovery(final WifiP2pManager.ActionListener listener) {
        Log.i(TAG, "Resetting service discovery");
        final WifiP2pServiceRequest request = serviceRequest;
        stopServiceDiscovery(new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "Reset service discovery - successfully stopped");
                continuouslyDiscoverServices(request);
                if(listener != null)
                    listener.onSuccess();
            }

            @Override
            public void onFailure(int reason) {
                Log.i(TAG, "Reset service discovery - failed to stop");
                if(listener != null)
                    listener.onFailure(reason);
            }
        });
    }

    public void resetServiceDiscovery() {
        resetServiceDiscovery(null);
    }

    /**
     * Submits a new task to initiate service discovery after the discovery
     * timeout period has expired
     */
    private void submitServiceDiscoveryTask(){
        Log.i(TAG, "Submitting service discovery task");
        // Discover times out after 2 minutes... but running discovery only every 2mins results in
        // long delays on various models. So we set this to the same time as the service rebroadcast
        // ... recommended 30s
        int timeToWait = peerDiscoveryInterval;
        ServiceDiscoveryTask serviceDiscoveryTask = new ServiceDiscoveryTask();
        Timer timer = new Timer();
        // Submit the service discovery task and add it to the list
        timer.schedule(serviceDiscoveryTask, timeToWait);
        serviceDiscoveryTasks.add(serviceDiscoveryTask);
    }

    /**
     * Timed task to initiate a new services discovery. Will recursively submit
     * a new task as long as isDiscovering is true
     */
    private class ServiceDiscoveryTask extends TimerTask {
        public void run() {
            discoverServices();
            // Submit the next task if a stop call hasn't been made
            if (isDiscovering) {
                submitServiceDiscoveryTask();
            }
            // Remove this task from the list since it's complete
            serviceDiscoveryTasks.remove(this);
        }
    }

    public Map<String, DnsSdService> getDnsSdServiceMap(){
        return dnsSdServiceMap;
    }

    public Map<String, DnsSdTxtRecord> getDnsSdTxtRecordMap() {
        return dnsSdTxtRecordMap;
    }

    /**
     * Uses wifiManager to determine if Wi-Fi is enabled
     * @return Whether Wi-Fi is enabled or not
     */
    public boolean isWifiEnabled() {
        return wifiManager.isWifiEnabled();
    }

    /**
     * Removes a registered local service.
     */
    public void removeService(final WifiP2pManager.ActionListener actionListener) {
        if(wifiP2pManager!=null){
            if(wifiP2pServiceInfo != null) {
                Log.i(TAG, "Removing local service");
                wifiP2pManager.removeLocalService(channel, wifiP2pServiceInfo, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        //Suspected issue: when wifi is re-enabled - this will succeed, set wifip2pserviceinfo to null, and make the subsequent addLocalService fail
                        //try shortening the time for re-enabling service discovery in networkmanagerandroid
                        Log.i(TAG, "Local service removed");
                        wifiP2pServiceInfo = null;
                        Intent intent = new Intent(Action.SERVICE_REMOVED);
                        localBroadcastManager.sendBroadcast(intent);
                        if(actionListener != null)
                            actionListener.onSuccess();
                    }

                    @Override
                    public void onFailure(int reason) {
                        Log.e(TAG, "Failure removing local service: " + FailureReason.fromInteger(reason).toString());
                        if(actionListener != null)
                            actionListener.onFailure(reason);
                    }
                });
            } else {
                Log.w(TAG, "No local service to remove");
                if(actionListener != null)
                    actionListener.onSuccess();
            }
        }else{
            Log.e(TAG,"WifiDirectHandler: removeService: wifip2pManager is null");
            if(actionListener != null)
                actionListener.onFailure(50);
        }
    }

    public void removeService() {
        removeService(null);
    }

    private void clearServiceDiscoveryRequests(final WifiP2pManager.ActionListener actionListener) {
        if (serviceRequest != null && wifiP2pManager!=null) {
            wifiP2pManager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    serviceRequest = null;
                    Log.i(TAG, "Service discovery requests cleared");
                    if(actionListener != null)
                        actionListener.onSuccess();
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failure clearing service discovery requests: " + FailureReason.fromInteger(reason).toString());
                    if(actionListener != null)
                        actionListener.onFailure(reason);
                }
            });
        }else{
            Log.e(TAG,"WifiDirectHandler: clearServiceDiscoveryRequests: wifip2pManager or serviceRequest might be null");
        }
    }

    private void clearServiceDiscoveryRequests() {
        clearServiceDiscoveryRequests(null);
    }




    /**
     * Initiates a connection to a service
     * @param service The service to connect to
     */
    public void initiateConnectToService(DnsSdService service) {
        if(wifiP2pManager!=null){
            // Device info of peer to connect to
            WifiP2pConfig wifiP2pConfig = new WifiP2pConfig();
            wifiP2pConfig.deviceAddress = service.getSrcDevice().deviceAddress;
            wifiP2pConfig.wps.setup = WpsInfo.PBC;

            // Starts a peer-to-peer connection with a device with the specified configuration
            wifiP2pManager.connect(channel, wifiP2pConfig, new WifiP2pManager.ActionListener() {
                // The ActionListener only notifies that initiation of connection has succeeded or failed

                @Override
                public void onSuccess() {
                    Log.i(TAG, "Initiating connection to service");
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failure initiating connection to service: " + FailureReason.fromInteger(reason).toString());
                }
            });
        }else{
            Log.e(TAG,"WifiDirectHandler: initiateConnectToService: wifip2pManager is null");
        }
    }

    /**
     * Creates a service that can be connected to without prompting. This is possible by creating an
     * access point and broadcasting the password for peers to use. Peers connect via normal wifi, not
     * wifi direct, but the effect is the same.
     *
     * On most devices the group will timeout after about 5 minutes if there has been no activity
     * (e.g. no peer connected to the group). The groups shutdown will trigger a connection state change
     * at which point we will re-create the group. Recreation leads to a new SSID and passphrase so
     * the service also needs updated.
     *
     * @param serviceData Service data (TXT records) to add to the created service. TXT records are
     *                    automatically added for the created SSID and passphrase
     * @param actionListener ActionListener that will be called when the whole process (create group,
     *                       clearLocalServices, addLocalService) has completed with onSuccess or onFailure.
     */
    public void startAddingNoPromptService(ServiceData serviceData, final @Nullable WifiP2pManager.ActionListener actionListener) {
        if(wifiP2pManager!=null){
            if (wifiP2pServiceInfo != null && addLocalService) {
                removeService();
            }
            setNoPromptServiceStatus(NOPROMPT_STATUS_GROUP_REQUESTED);
            noPromptServiceData = serviceData;
            noPromptActionListener = actionListener;

            wifiP2pManager.createGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    setNoPromptServiceStatus(NOPROMPT_STATUS_GROUP_CREATED);
                    Log.i(TAG, "Group created successfully");
                    if(noPromptActionListener!=null && !addLocalService){
                        noPromptActionListener.onSuccess();
                        WifiDirectHandler.this.noPromptActionListener = null;
                    }
                    //Note that you will have to wait for WIFI_P2P_CONNECTION_CHANGED_INTENT for group info
                    //The next stage is handled by onGroupInfoAvailable triggered by connection change
                }

                @Override
                public void onFailure(int reason) {
                    setNoPromptServiceStatus(NOPROMPT_STATUS_FAILED_ADDING_GROUP);
                    Log.i(TAG, "Group creation failed: " + FailureReason.fromInteger(reason));
                    if(noPromptActionListener != null) {
                        noPromptActionListener.onFailure(reason);
                        WifiDirectHandler.this.noPromptActionListener = null;
                    }
                }
            });
        }else{
            Log.e(TAG,"WifiDirectHandler: startAddingNoPromptService: wifip2pManager is null");
        }
    }

    /**
     * Creates a service that can be connected to without prompting. This is possible by creating an
     * access point and broadcasting the password for peers to use. Peers connect via normal wifi, not
     * wifi direct, but the effect is the same.
     *
     * @param serviceData Service data (TXT records) to add to the created service. TXT records are
     *                    automatically added for the created SSID and passphrase
     */
    public void startAddingNoPromptService(ServiceData serviceData) {
        startAddingNoPromptService(serviceData, null);
    }

    /**
     * Connects to a no prompt service.
     *
     * When the attempt to connect to the network succeeds or fails
     * An Intent with the Action Action.NOPROMPT_NETWORK_CONNECTIVITY_ACTION will be broadcast. It will
     * have a boolean extra EXTRA_NOPROMPT_NETWORK_SUCCEEDED indicating if the connection succeeded
     * or not.
     *
     * @param txtRecord The DNS SD TXT Record of the service we want to connect with.
     */
    public void connectToNoPromptService(DnsSdTxtRecord txtRecord) {
        if(wifiManager!=null){
            WifiConfiguration configuration = new WifiConfiguration();
            noPromptNetworkSsidExpected= txtRecord.getRecord().get(Keys.NO_PROMPT_NETWORK_NAME);
            configuration.SSID = "\"" + txtRecord.getRecord().get(Keys.NO_PROMPT_NETWORK_NAME) + "\"";
            configuration.preSharedKey = "\"" + txtRecord.getRecord().get(Keys.NO_PROMPT_NETWORK_PASS) + "\"";
            int netId = wifiManager.addNetwork(configuration);
            //disconnect form current network and connect to this one
            wifiManager.disconnect();
            wifiManager.enableNetwork(netId, true);
            wifiManager.reconnect();
            Log.i(TAG, "Connected to no prompt network");
        }
    }


    /**
     * Getting currently connected network SSID so that the connection can be checked
     * if it is NoPrompt connection or not.
     * @return ssid current connected hotspot SSID
     */
    public WifiInfo getCurrentConnectedWifiInfo() {
        ConnectivityManager connManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connManager.getActiveNetworkInfo();

        if (networkInfo != null && networkInfo.getType() == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected()) {
            WifiManager wifiManager = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wifiManager.getConnectionInfo();

        }
        return null;
    }




    /**
     * Connect to a no prompt service using the DnsSdService. The SSID and passphrase are in fact
     * contained in the SD TXT records. The Android documentation does not specify whether the service
     * discovered or txt record available method will be called first. The txt record available
     * method however contains the full domain (containing the service name) and it therefor contains
     * all required info. Thus it seems like a better idea to connect to the no prompt service using
     * the DnsSdTxtRecord rather than the DnsSdService.
     *
     * @param service
     */
    public void connectToNoPromptService(DnsSdService service) {
        DnsSdTxtRecord txtRecord = dnsSdTxtRecordMap.get(service.getSrcDevice().deviceAddress);
        if(txtRecord == null) {
            Log.e(TAG, "No dnsSdTxtRecord found for the no prompt service");
            return;
        }
        connectToNoPromptService(txtRecord);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String action = intent.getAction();

        if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            // The list of discovered peers has changed
            handlePeersChanged(intent);
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            // The state of Wi-Fi P2P connectivity has changed
            handleConnectionChanged(intent);
        } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            // Indicates whether Wi-Fi P2P is enabled
            handleP2pStateChanged(intent);
        } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            // Indicates this device's configuration details have changed
            handleThisDeviceChanged(intent);
        } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
            handleWifiStateChanged(intent);
        } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
            //This causes problems connecting to a no prompt network - temporarily commenting it out
            //handleScanResultsAvailable(intent);
        }else if(ConnectivityManager.CONNECTIVITY_ACTION.equals(action)){
            //handle connection (Successfully connected/disconnected)
            handleConnectivityAction(intent);
        }
    }

    private void handleConnectivityAction(Intent intent){
        Log.i(TAG, "Wi-Fi connectivity action");
        if(noPromptNetworkSsidExpected != null) {
            ConnectivityManager connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            /*
             When connecting to a no prompt network the connectivity_action intent will be fired. The
             first intent received after attempting to connect is therefor the result of attempting to
             connect.
             */


            if(networkInfo != null && networkInfo.getType() == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected()) {
                isNoPromptNetworkConnected = noPromptNetworkSsidExpected.equals(getCurrentConnectedWifiInfo().getSSID().replace("\"", ""));
            }



            Log.i(TAG, "Attempt to connect to no prompt group: "
                    + (isNoPromptNetworkConnected ? "succeeded" : "failed"));
            noPromptNetworkSsidExpected = null;

            Intent connectionIntent = new Intent(Action.NOPROMPT_NETWORK_CONNECTIVITY_ACTION);
            connectionIntent.putExtra(EXTRA_NOPROMPT_NETWORK_SUCCEEDED, isNoPromptNetworkConnected);
            localBroadcastManager.sendBroadcast(connectionIntent);
        }
    }

    private void handleWifiStateChanged(Intent intent) {
        Log.i(TAG, "Wi-Fi state changed");
        int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
        if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
            // Register app with Wi-Fi P2P framework, register WifiDirectBroadcastReceiver
            Log.i(TAG, "Wi-Fi enabled");
            taskQueue.queueTask(new ActionListenerTask("wifi enabled - register") {
                @Override
                public void run(WifiP2pManager.ActionListener listener) {
                    registerP2p();
                    registerP2pReceiver();
                    listener.onSuccess();
                }
            });
        } else if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
            // Remove local service, unregister app with Wi-Fi P2P framework, unregister P2pReceiver
            Log.i(TAG, "Wi-Fi disabled");

            taskQueue.queueTask(new ActionListenerTask("wifi disabled - stop service discovery") {
                @Override
                public void run(WifiP2pManager.ActionListener listener) {
                    stopServiceDiscovery(listener);
                }
            })
            .queueTask(new ActionListenerTask("wifi disabled - stop peer discovery") {
                @Override
                public void run(WifiP2pManager.ActionListener listener) {
                    stopPeerDiscovery();
                    listener.onSuccess();
                }
            })
            .queueTask(new ActionListenerTask("wifi disabled - clear service discovery requests") {
                @Override
                public void run(WifiP2pManager.ActionListener listener) {
                    clearServiceDiscoveryRequests(listener);
                }
            });

            if(wifiP2pServiceInfo != null){
                taskQueue.queueTask(new ActionListenerTask("wifi disabled - remove service") {
                    @Override
                    public void run(WifiP2pManager.ActionListener listener) {
                        removeService(listener);
                    }
                });
            }

            taskQueue.queueTask(new ActionListenerTask("wifi disabled - remove group") {
                @Override
                public void run(WifiP2pManager.ActionListener listener) {
                    wifiP2pServiceInfo = null;
                    removeGroup(listener);
                }

                @Override
                public void onFailure(int reason) {
                    Log.i(TAG, "Remove group failure reported after wifi disabled - but its gone");
                    wifiP2pGroup = null;
                    groupFormed = false;
                    isGroupOwner = false;
                }
            })
            .queueTask(new ActionListenerTask("wifi disabled - finish") {
                @Override
                public void run(WifiP2pManager.ActionListener listener) {
                    removePersistentGroups();
                    unregisterP2pReceiver();
                    unregisterP2p();
                    listener.onSuccess();
                }
            });
        }
        localBroadcastManager.sendBroadcast(new Intent(Action.WIFI_STATE_CHANGED));
    }

    private void handlePeersChanged(Intent intent) {
        Log.i(TAG, "List of discovered peers changed");
        if (wifiP2pManager != null) {
            // Request the updated list of discovered peers from wifiP2PManager
            wifiP2pManager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                @Override
                public void onPeersAvailable(WifiP2pDeviceList peers) {
                    synchronized (wifiP2pDeviceLock) {
                        wifiP2pDeviceList = peers;
                    }

                    Intent intent = new Intent(Action.PEERS_CHANGED);
                    intent.putExtra(Extra.PEERS, peers);
                    localBroadcastManager.sendBroadcast(intent);
                }
            });
        }
    }

    /**
     * The state of Wi-Fi P2P connectivity has changed
     * Here is where you can request group info
     * Available extras: EXTRA_WIFI_P2P_INFO, EXTRA_NETWORK_INFO, EXTRA_WIFI_P2P_GROUP
     * @param intent
     */
    private void handleConnectionChanged(Intent intent) {
        Log.i(TAG, "Wi-Fi P2P Connection Changed");

        if(wifiP2pManager == null) {
            return;
        }
        // Extra information from EXTRA_NETWORK_INFO
        NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
        if(networkInfo.isConnected()) {
            Intent connectionIntent = new Intent(Action.WIFI_DIRECT_CONNECTION_CHANGED);
            connectionIntent.putExtra(EXTRA_WIFIDIRECT_CONNECTION_SUCCEEDED, networkInfo.isConnected());
            localBroadcastManager.sendBroadcast(connectionIntent);
            Log.i(TAG, "Connected to P2P network. Requesting connection info");
            wifiP2pManager.requestConnectionInfo(channel, WifiDirectHandler.this);
        } else {
            Intent disconnected = new Intent(Action.WIFI_DIRECT_CONNECTION_CHANGED);
            localBroadcastManager.sendBroadcast(disconnected);
            wifiP2pManager.requestConnectionInfo(channel, WifiDirectHandler.this);
        }

        // Requests peer-to-peer group information
        wifiP2pManager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup wifiP2pGroup) {
                WifiDirectHandler.this.wifiP2pGroup = wifiP2pGroup;
                Log.i(TAG, "Group info available");
                Log.i(TAG, "WifiP2pGroup:");
                Log.i(TAG, p2pGroupToString(wifiP2pGroup));
                if (wifiP2pGroup != null) {

                    WifiDirectHandler.this.wifiP2pGroup = wifiP2pGroup;
                    int noPromptServiceStatus = getNoPromptServiceStatus();
                    if(noPromptServiceStatus > NOPROMPT_STATUS_INACTIVE && noPromptServiceStatus < NOPROMPT_STATUS_ADDING_LOCAL_SERVICE) {
                        WifiDirectHandler.this.setNoPromptServiceStatus(NOPROMPT_STATUS_ADDING_LOCAL_SERVICE);
                        noPromptServiceData.getRecord().put(Keys.NO_PROMPT_NETWORK_NAME,
                                wifiP2pGroup.getNetworkName());
                        noPromptServiceData.getRecord().put(Keys.NO_PROMPT_NETWORK_PASS,
                                wifiP2pGroup.getPassphrase());

                        if(addLocalService){

                            WifiDirectHandler.this.addLocalService(noPromptServiceData.getServiceName(),
                                    noPromptServiceData.getRecord(), new WifiP2pManager.ActionListener() {
                                        @Override
                                        public void onSuccess() {
                                            WifiDirectHandler.this.setNoPromptServiceStatus(NOPROMPT_STATUS_ACTIVE);
                                            Log.i(TAG, "Successfully added local service for no-prompt group");
                                            if(noPromptActionListener != null) {
                                                noPromptActionListener.onSuccess();
                                                noPromptActionListener = null;
                                            }
                                        }

                                        @Override
                                        public void onFailure(int reason) {
                                            WifiDirectHandler.this.setNoPromptServiceStatus(NOPROMPT_STATUS_FAILED_ADDING_LOCAL_SERVICE);
                                            Log.e(TAG, "Failed to add local service for no-prompt group"
                                                    + FailureReason.fromInteger(reason));
                                            if(noPromptActionListener != null) {
                                                noPromptActionListener.onFailure(reason);
                                                noPromptActionListener = null;
                                            }
                                        }
                                    });
                        }else{
                            WifiDirectHandler.this.setNoPromptServiceStatus(NOPROMPT_STATUS_ACTIVE);
                        }
                    }

                    //add intent
                    localBroadcastManager.sendBroadcast(new Intent(Action.GROUP_INFO_AVAILABLE));
                }
            }
        });
    }


    /**
     * Indicates whether Wi-Fi P2P is enabled
     * Determine if Wi-Fi P2P mode is enabled or not, alert the Activity
     * Available extras: EXTRA_WIFI_STATE
     * Sticky Intent
     * @param intent
     */

    private void handleP2pStateChanged(Intent intent) {
        Log.i(TAG, "Wi-Fi P2P State Changed:");
        int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
        boolean isWifiP2pEnabled;
        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
            // Wi-Fi Direct is enabled
            isWifiP2pEnabled = true;
            Log.i(TAG, "- Wi-Fi Direct is enabled");
        } else {
            // Wi-Fi Direct is not enabled
            isWifiP2pEnabled = false;
            Log.i(TAG, "- Wi-Fi Direct is not enabled");
        }
    }

    /**
     * Indicates this device's configuration details have changed
     * Sticky Intent
     * @param intent
     */
    private void handleThisDeviceChanged(Intent intent) {
        Log.i(TAG, "This device changed");

        // Extra information from EXTRA_WIFI_P2P_DEVICE
        thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);

        // Logs extra information from EXTRA_WIFI_P2P_DEVICE
        Log.i(TAG, "handleThisDeviceChanged: " + p2pDeviceToString(thisDevice));

        if(getNoPromptServiceStatus() == NOPROMPT_STATUS_ACTIVE) {
            //check to see if the noprompt group has timed out and needs recreated
            Log.i(TAG, "handleThisDeviceChanged: Check no prompt group status");
            wifiP2pManager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    if(WifiDirectHandler.this.wifiP2pGroup != null){
                        //this is really already taken care of in handleConnectionChanged
                    }

                    if(group == null && WifiDirectHandler.this.getNoPromptServiceStatus() == NOPROMPT_STATUS_ACTIVE) {
                        Log.i(TAG, "handleThisDeviceChanged: Detected noprompt group timed out - recreating...");
                        localBroadcastManager.sendBroadcast(new Intent(Action.NOPROMPT_SERVICE_TIMEOUT_ACTION));
                        WifiDirectHandler.this.setNoPromptServiceStatus(NOPROMPT_STATUS_TIMEDOUT);
                        WifiDirectHandler.this.removePersistentGroups();

                        WifiDirectHandler.this.startAddingNoPromptService(noPromptServiceData,
                                noPromptActionListener);

                    }
                }
            });
        }

        localBroadcastManager.sendBroadcast(new Intent("DeviceStatus:"+String.valueOf(thisDevice.status)));
        localBroadcastManager.sendBroadcast(new Intent(Action.DEVICE_CHANGED));
    }


    /**
     * Toggle wifi
     * @param wifiEnabled whether or not wifi should be enabled
     */
    public void setWifiEnabled(boolean wifiEnabled) {
        wifiManager.setWifiEnabled(wifiEnabled);
    }

    public Handler getHandler() {
        return handler;
    }

    // TODO: Add JavaDoc
    @Override
    public boolean handleMessage(Message msg) {
        Log.i(TAG, "handleMessage() called");
        switch (msg.what) {
            case MESSAGE_READ:
                byte[] readBuf = (byte[]) msg.obj;
                // construct a string from the valid bytes in the buffer
                String receivedMessage = new String(readBuf, 0, msg.arg1);
                Log.i(TAG, "Received message: " + receivedMessage);
                Intent messageReceivedIntent = new Intent(Action.MESSAGE_RECEIVED);
                messageReceivedIntent.putExtra(MESSAGE_KEY, readBuf);
                localBroadcastManager.sendBroadcast(messageReceivedIntent);
                break;
            case MY_HANDLE:
                Object messageObject = msg.obj;
                communicationManager = (CommunicationManager) messageObject;
                localBroadcastManager.sendBroadcast(new Intent(Action.SERVICE_CONNECTED));
                break;
            case COMMUNICATION_DISCONNECTED:
                Log.i(TAG, "Handling communication disconnect");
                localBroadcastManager.sendBroadcast(new Intent(Action.WIFI_DIRECT_CONNECTION_CHANGED));
                break;
        }
        return true;
    }

    /**
     * Allows for binding to the service.
     */
    public class WifiTesterBinder extends Binder {
        public WifiDirectHandler getService() {
            return WifiDirectHandler.this;
        }
    }

    /**
     * Actions that can be broadcast or received by the handler
     */
    public class Action {
        public static final String DNS_SD_TXT_RECORD_AVAILABLE = "dnsSdTxtRecordAdded",
                DNS_SD_SERVICE_AVAILABLE = "dnsSdServiceAvailable",
                SERVICE_REMOVED = "serviceRemoved",
                PEERS_CHANGED = "peersChanged",
                SERVICE_CONNECTED = "serviceConnected",
                DEVICE_CHANGED = "deviceChanged",
                MESSAGE_RECEIVED = "messageReceived",
                WIFI_STATE_CHANGED = "wifiStateChanged",
                NOPROMPT_SERVICE_TIMEOUT_ACTION="noPromptServiceTimedOutAction",
                WIFI_DIRECT_CONNECTION_CHANGED = "wifiDirectConnectionChange",
                NOPROMPT_NETWORK_CONNECTIVITY_ACTION ="noPromptNetworkConnectivityAction",
                GROUP_INFO_AVAILABLE ="groupInfoAvailableAction",
                NOPROMPT_SERVICE_CREATED_ACTION = "noPromptServiceCreatedAction",
                CONNECTION_INFO_AVAILABLE = "connectionInfoAvailable",
                CONNECT_TO_NORMAL_WIFI_DIRECT_RESULT = "wifiDirectNormalResult",
                CANCEL_CONNECT_TO_NORMAL_WIFI_DIRECT_RESULT = "cancelWifiDirectNormalResult";
    }

    public class Extra {
        public static final String PEERS = "peers";
    }


    private class Keys {
        public static final String NO_PROMPT_NETWORK_NAME = "networkName",
                NO_PROMPT_NETWORK_PASS = "passphrase";
    }

    // TODO: Add JavaDoc
    private class WifiDirectBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            onHandleIntent(intent);
        }
    }

    // TODO: Add JavaDoc
    private class WifiBroadcastReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            onHandleIntent(intent);
        }
    }

    /**
     * Takes a WifiP2pDevice and returns a String of readable device information
     * @param wifiP2pDevice
     * @return
     */
    public String p2pDeviceToString(WifiP2pDevice wifiP2pDevice) {
        if (wifiP2pDevice != null) {
            String strDevice = "Device name: " + wifiP2pDevice.deviceName;
            strDevice += "\nDevice address: " + wifiP2pDevice.deviceAddress;
            if (wifiP2pDevice.equals(thisDevice)) {
                strDevice += "\nIs group owner: " + isGroupOwner();
            } else {
                strDevice += "\nIs group owner: false";
            }
            strDevice += "\nStatus: " + deviceStatusToString(wifiP2pDevice.status) + "\n";
            return strDevice;
        } else {
            Log.e(TAG, "WifiP2pDevice is null");
            return "";
        }
    }

    public String p2pInfoToString(WifiP2pInfo wifiP2pInfo) {
        if (wifiP2pInfo != null) {
            String strWifiP2pInfo = "Group formed: " + wifiP2pInfo.groupFormed;
            strWifiP2pInfo += "\nIs group owner: " + wifiP2pInfo.isGroupOwner;
            strWifiP2pInfo += "\nGroup owner address: " + wifiP2pInfo.groupOwnerAddress;
            return strWifiP2pInfo;
        } else {
            Log.e(TAG, "WifiP2pInfo is null");
            return "";
        }
    }

    public String p2pGroupToString(WifiP2pGroup wifiP2pGroup) {
        if (wifiP2pGroup != null) {
            String strWifiP2pGroup = "Network name: " + wifiP2pGroup.getNetworkName();
            strWifiP2pGroup += "\nIs group owner: " + wifiP2pGroup.isGroupOwner();
            if (wifiP2pGroup.getOwner() != null) {
                strWifiP2pGroup += "\nGroup owner: ";
                strWifiP2pGroup += "\n" + p2pDeviceToString(wifiP2pGroup.getOwner());
            }
            if (wifiP2pGroup.getClientList() != null && !wifiP2pGroup.getClientList().isEmpty()) {
                for (WifiP2pDevice client : wifiP2pGroup.getClientList()) {
                    strWifiP2pGroup += "\nClient: ";
                    strWifiP2pGroup += "\n" + p2pDeviceToString(client);
                }
            }
            return strWifiP2pGroup;
        } else {
            Log.e(TAG, "WifiP2pGroup is null");
            return "";
        }
    }

    /**
     * Connect device to WiFi-Direct "normally" (e.g. without using the legacy mode of connecting
     * to a group).
     *
     * Listen for the broadcast of CONNECT_TO_NORMAL_WIFI_DIRECT_RESULT. This will contain two extras:
     *  EXTRA_CONNECT_TO_NORMAL_WIFI_DIRECT_MAC_ADDR : the mac address the result is for
     *  EXTRA_WIFIDIRECT_CONNECTION_SUCCEEDED : a boolean indicating if the connection was successful
     *  (or not).
     *
     * Shortly thereafter an Action.CONNECTION_INFO_AVAILABLE will be broadcast. This is when the
     * wifip2pinfo will be updated, and when the group owner and group owner IP address will be known.
     *
     * Finally an Action.GROUP_INFO_AVAILABLE will be broadcast. This is when information about other
     * group members including their mac addresses and device names (but not IP addresses) will be
     * available.
     *
     * This also triggers a Action.WIFI_DIRECT_CONNECTION_CHANGED broadcast . It will contain a boolean
     * EXTRA_WIFIDIRECT_CONNECTION_SUCCEEDED boolean set to true if the connection is successful.
     *
     * On the other device, when the connection is successfully accepted, an
     * Action.WIFI_DIRECT_CONNECTION_CHANGED and Action.CONNECTION_INFO_AVAILABLE will be broadcast.
     *
     * @param deviceMacAddress The mac address of the device to connect to. For some reason this seems
     *                         to be case sensitive (kept in lower case).
     * @param groupOwnerIntent A integer between 0 and 15. 0 = Almost certain to be a client,
     *                         15 = Become group owner. Pass -1 to skip setting this parameter.
     */
    public void connectToNormalWifiDirect(final String deviceMacAddress, int groupOwnerIntent){
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceMacAddress;
        config.wps.setup = WpsInfo.PBC;
        if(groupOwnerIntent >= 0) {
            config.groupOwnerIntent = groupOwnerIntent;
        }


        final Intent resultIntent = new Intent(Action.CONNECT_TO_NORMAL_WIFI_DIRECT_RESULT);
        resultIntent.putExtra(EXTRA_CONNECT_TO_NORMAL_WIFI_DIRECT_MAC_ADDR, deviceMacAddress);
        Log.i(TAG, "WiFi direct: connecting using 'normal' wifi direct to : " + deviceMacAddress);
        wifiP2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG,"WiFi Direct connection to " + deviceMacAddress + " succeeded");
                resultIntent.putExtra(EXTRA_WIFIDIRECT_CONNECTION_SUCCEEDED, true);
                localBroadcastManager.sendBroadcast(resultIntent);
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG,"Device connection to " + deviceMacAddress + "failed "
                        +FailureReason.fromInteger(reason));
                resultIntent.putExtra(EXTRA_WIFIDIRECT_CONNECTION_SUCCEEDED, false);
                localBroadcastManager.sendBroadcast(resultIntent);
            }
        });
    }

    /**
     * Connect device to normal WiFi-Direct without group
     *
     * @param deviceMacAddress The mac address of the device to connect to. For some reason this seems
     *                         to be case sensitive (kept in lower case).
     */
    public void connectToNormalWifiDirect(String deviceMacAddress) {
        connectToNormalWifiDirect(deviceMacAddress, -1);
    }

    /**
     * Cancel any ongoing creation / negotiation of a wifi p2p group as per the WifiP2pManager
     * cancelConnect method.
     *
     * Action.CANCEL_CONNECT_TO_NORMAL_WIFI_DIRECT_RESULT with the extras:
     *    EXTRA_CANCEL_CONNECT_TO_NORMAL_WIFI_DIRECT_SUCCEEDED - Boolean for succeess/fail.
     *
     */
    public void cancelConnectToNormalWifiDirect() {
        Log.i(TAG, "cancelConnectToNormalWifiDirect");
        final Intent resultIntent = new Intent(Action.CANCEL_CONNECT_TO_NORMAL_WIFI_DIRECT_RESULT);
        wifiP2pManager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "cancelConnectToNormalWifiDirect : succeeded");
                resultIntent.putExtra(EXTRA_CANCEL_CONNECT_TO_NORMAL_WIFI_DIRECT_SUCCEEDED,
                        true);
                localBroadcastManager.sendBroadcast(resultIntent);
            }

            @Override
            public void onFailure(int i) {
                Log.i(TAG, "cancelConnectToNormalWifiDirect :failed");
                resultIntent.putExtra(EXTRA_CANCEL_CONNECT_TO_NORMAL_WIFI_DIRECT_SUCCEEDED,
                        false);
                localBroadcastManager.sendBroadcast(resultIntent);
            }
        });
    }

    /**
     * Translates a device status code to a readable String status
     * @param status
     * @return A readable String device status
     */
    public String deviceStatusToString(int status) {
        if (status == WifiP2pDevice.AVAILABLE) {
            return "Available";
        } else if (status == WifiP2pDevice.INVITED) {
            return "Invited";
        } else if (status == WifiP2pDevice.CONNECTED) {
            return "Connected";
        } else if (status == WifiP2pDevice.FAILED) {
            return "Failed";
        } else if (status == WifiP2pDevice.UNAVAILABLE) {
            return "Unavailable";
        } else {
            return "Unknown";
        }
    }

    /**
     * Decide whether to broadcast local service after group creation
     * @param addLocalService
     * boolean TRUE= broadcast local service (default)
     * boolean FALSE= Don't broadcast local service
     */
    public void setAddLocalServiceAfterGroupCreation(boolean addLocalService){
        this.addLocalService=addLocalService;
    }

    public String getThisDeviceInfo() {
        if (thisDevice == null) {
            return "No Device Info";
        } else {
            if (thisDevice.deviceName.equals("")) {
                thisDevice.deviceName = "Android Device";
            }
            return p2pDeviceToString(thisDevice);
        }
    }

    public boolean isGroupOwner() {
        return this.isGroupOwner;
    }



    public boolean isGroupFormed() {
        return this.groupFormed;
    }

    public boolean isDiscovering() {
        return this.isDiscovering;
    }

    public boolean isNoPromptNetworkConnected(){
        return isNoPromptNetworkConnected;
    }

    public WifiP2pDevice getThisDevice() {
        return this.thisDevice;
    }

    public WifiP2pServiceInfo getWifiP2pServiceInfo() {
        return this.wifiP2pServiceInfo;
    }

    public ServiceData getNoPromptServiceData(){
        return noPromptServiceData;
    }

    public List<ScanResult> getWifiScanResults() {
        return wifiScanResults;
    }

    public WifiP2pManager.Channel getWiFiDirectChannel(){
        return this.channel;
    }

    public WifiP2pManager getWifiDirectP2pManager(){
        return this.wifiP2pManager;
    }
    public WifiP2pGroup getWifiP2pGroup(){
        return this.wifiP2pGroup;
    }

    public WifiP2pInfo getWifiP2pInfo(){
        return wifiP2pInfo;
    }

    public CommunicationManager getCommunicationManager() {
        return communicationManager;
    }

    public int getServiceStatus(){
        return serviceStatus;
    }

    public WifiP2pDevice getPeerByDeviceAddress(String deviceAddress) {
        synchronized (wifiP2pDeviceLock) {
            if(wifiP2pDeviceList != null) {
                if(Build.VERSION.SDK_INT >= 18) {
                    return wifiP2pDeviceList.get(deviceAddress);
                }else {
                    for(WifiP2pDevice device : wifiP2pDeviceList.getDeviceList()) {
                        if(device.deviceAddress != null && device.deviceAddress.equals(deviceAddress))
                            return device;
                    }

                    return null;
                }
            }else {
                return null;
            }
        }
    }

    public WifiP2pDeviceList getWifiP2pDeviceList() {
        return wifiP2pDeviceList;
    }


    /**
     * When a local service is added it should send out UDP broadcasts for anyone listening, and
     * thereafter it should respond automatically to service discovery requests sent out by peers.
     * Sometimes responding to service discovery requests from peers is flaky, leading to some online
     * to recommend regularly calling discoverPeers to force out a new UDP broadcast.
     *
     * Unfortunately when this is done it appears to break being able to simultaneously both discover
     * service and having a local service (e.g. a true network of peers where peers can both discover
     * and be discovered).
     *
     * If this node is only to be used to be discovered by others, and does not need to disover other
     * nodes it might be worth setting this to true.
     *
     * @param localServiceRequiresPeerDiscoveryKick true for enabled, false for disabled.
     */
    public void setLocalServicePeerDiscoveryKickEnabled(boolean localServiceRequiresPeerDiscoveryKick) {
        this.localServicePeerDiscoveryKickEnabled = localServiceRequiresPeerDiscoveryKick;
    }

    /**
     * Determine if discover peer kick is enabled or disabled
     * @see #setLocalServicePeerDiscoveryKickEnabled(boolean)
     *
     * @return True if enabled, false otherwise.
     */
    public boolean isLocalServicePeerDiscoveryKickEnabled() {
        return localServicePeerDiscoveryKickEnabled;
    }

    public static void actionListenerSuccess(@Nullable WifiP2pManager.ActionListener listener){
        if(listener != null)
            listener.onSuccess();
    }

    public static void actionListenerFailure(@Nullable WifiP2pManager.ActionListener listener, int reason) {
        if(listener != null) {
            listener.onFailure(reason);
        }
    }

}