package org.telehash.dht;

import org.telehash.json.JSONArray;
import org.telehash.json.JSONObject;
import org.telehash.core.Channel;
import org.telehash.core.ChannelHandler;
import org.telehash.core.ChannelPacket;
import org.telehash.core.CipherSetIdentifier;
import org.telehash.core.CounterTrigger;
import org.telehash.core.FingerprintSet;
import org.telehash.core.HashName;
import org.telehash.core.Line;
import org.telehash.core.LocalNode;
import org.telehash.core.Log;
import org.telehash.core.Node;
import org.telehash.core.PeerNode;
import org.telehash.core.SeeNode;
import org.telehash.core.SeedNode;
import org.telehash.core.Telehash;
import org.telehash.core.TelehashException;
import org.telehash.core.Util;
import org.telehash.crypto.CipherSet;
import org.telehash.crypto.HashNamePublicKey;
import org.telehash.network.InetPath;
import org.telehash.network.Path;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DHT {

    private static final String SEEK_TYPE = "seek";
    public static final String LINK_TYPE = "link";
    private static final String CONNECT_TYPE = "connect";
    private static final String SEEK_KEY = "seek";
    public static final String SEED_KEY = "seed";
    public static final String SEE_KEY = "see";

    public static final String PEER_TYPE = "peer";
    public static final String PEER_KEY = "peer";

    private Telehash mTelehash;
    private Set<SeedNode> mInitialSeeds;
    private NodeTracker mNodeTracker;
    private Object mInitFinishedLock = new Object();
    private boolean mInitFinished = false;

    public DHT(Telehash telehash, LocalNode localNode, Set<SeedNode> seeds) {
        mTelehash = telehash;
        mNodeTracker = new NodeTracker(localNode);
        mInitialSeeds = seeds;
    }

    /** intentionally package-private */
    NodeTracker getNodeTracker() {
        return mNodeTracker;
    }

    public void init() {
        // register to receive channel packets for our types
        mTelehash.getSwitch().registerChannelHandler(SEEK_TYPE, mChannelHandler);
        mTelehash.getSwitch().registerChannelHandler(LINK_TYPE, mChannelHandler);
        mTelehash.getSwitch().registerChannelHandler(PEER_TYPE, mChannelHandler);
        mTelehash.getSwitch().registerChannelHandler(CONNECT_TYPE, mChannelHandler);

        // set a counter trigger to start bucket refresh when
        // all seed links have either been established or errored out.
        CounterTrigger trigger = new CounterTrigger(new Runnable() {
            @Override
            public void run() {
                Log.i("DHT: begin bucket refresh");
                mNodeTracker.refreshBuckets(new Runnable() {
                    @Override
                    public void run() {
                        Log.i("DHT: init finished");
                        synchronized (mInitFinishedLock) {
                            mInitFinished = true;
                            mInitFinishedLock.notifyAll();
                        }
                    }
                });
            }}
        );

        // open link channels to all seeds
        if (mInitialSeeds != null && (! mInitialSeeds.isEmpty())) {
            Log.i("DHT: link to initial seeds");
            for (SeedNode node : mInitialSeeds) {
                mNodeTracker.openLink(node, trigger);
            }
            trigger.setLimit(mInitialSeeds.size());
        } else {
            trigger.setLimit(0);
        }
    }

    public void waitForInit() {
        synchronized (mInitFinishedLock) {
            while (! mInitFinished) {
                try {
                    mInitFinishedLock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void dump() {
        mNodeTracker.dump();
    }

    public void close() {
        mNodeTracker.dump();
    }

    public void handleNewLine(Line line) {
        // TODO: only track a node if we have a link channel open.
        //mNodeTracker.submitNode(line.getRemotePeerNode());
        // TODO: relay to refresher?
    }

    public void submitNode(Node node) {
        Log.i("submitNode "+node);
        // TODO
        // 1. is a line already open for this node?
        // 2. open line
        // 3. open link channel
    }

    /**
     * Return a random hashname located with the specified bucket
     * (relative to the provided origin hashname).
     *
     * @param originHashName The origin hashname.  (i.e., your own hashname.)
     * @param bucket The bucket index.
     * @return The random hashname within the bucket.
     */
    public static HashName getRandomHashName(HashName originHashName, int bucket) {
        // start with the origin hashname
        BigInteger hash = new BigInteger(1, originHashName.getBytes());

        // randomize all bits below the bucket bit
        if (bucket > 0) {
            byte[] randomBytes = Telehash.get().getCrypto().getRandomBytes(HashName.SIZE);
            BigInteger random = new BigInteger(1, randomBytes);
            BigInteger mask = BigInteger.ONE.shiftLeft(bucket).subtract(BigInteger.ONE);

            hash = hash.andNot(mask);  // chop off right part of origin
            random = random.and(mask); // chop off left part of random
            hash = hash.or(random);    // combine left-origin and right-random
        }

        // flip the bucket bit
        hash = hash.flipBit(bucket);

        // the byte array may have an extra leading byte to hold the sign bit,
        // so trim to size.
        byte[] randomHashNameBytes = Util.fixedSizeBytes(hash.toByteArray(), HashName.SIZE);
        return new HashName(randomHashNameBytes);
    }

    private ChannelHandler mChannelHandler = new ChannelHandler() {
        @Override
        public void handleError(Channel channel, Throwable error) {

        };
        @Override
        public void handleIncoming(Channel channel, ChannelPacket channelPacket) {
            Log.i("DHT incoming: "+channelPacket);
            String type = channelPacket.getType();
            if (type == null) {
                return;
            }
            try {
                if (type.equals(SEEK_TYPE)) {
                    handleSeek(channel, channelPacket);
                } else if (type.equals(LINK_TYPE)) {
                    mNodeTracker.acceptLink(channel, channelPacket);
                } else if (type.equals(PEER_TYPE)) {
                    handlePeer(channel, channelPacket);
                } else if (type.equals(CONNECT_TYPE)) {
                    handleConnect(channel, channelPacket);
                }
            } catch (Throwable e) {
                // best-effort only
                e.printStackTrace();
            }
        }
        @Override
        public void handleOpen(Channel channel) {
            // not needed since this is a listening channel.
        };
    };

    private static final int MAX_SEEK_NODES_RETURNED = 9;

    private void handleSeek(Channel channel, ChannelPacket channelPacket) {
        String seekString = (String) channelPacket.get(SEEK_KEY);
        if (seekString == null || seekString.isEmpty()) {
            return;
        }

        // support prefix seeks by coercing the string to 64 characters.
        if (seekString.length() < HashName.SIZE*2) {
            char[] chars = new char[HashName.SIZE*2];
            Arrays.fill(chars, '0');
            seekString.getChars(0, seekString.length(), chars, 0);
            seekString = new String(chars);
        }

        HashName target = new HashName(Util.hexToBytes(seekString));
        Set<PeerNode> nodes = mNodeTracker.getClosestNodes(target, MAX_SEEK_NODES_RETURNED);

        StringBuilder log = new StringBuilder();
        log.append("processing: seek "+target+"\n");

        JSONArray seeArray = new JSONArray();
        for (PeerNode node : nodes) {
            if (node.getPath() instanceof InetPath) {
                PeerNode requestingNode =
                        (PeerNode)channel.getLine().getRemoteNode();
                CipherSetIdentifier csid = PeerNode.bestCipherSet(requestingNode, node);
                if (csid == null) {
                    Log.e("seek handling: cannot determine best cipher set match between nodes");
                    continue;
                }
                SeeNode see = new SeeNode(node.getHashName(), node.getActiveCipherSetIdentifier(), node.getPath());
                seeArray.put(see.render());
                log.append("\tsee: " + see.render()+"  node="+node+"\n");

            }
        }
        Log.i(log.toString());
        Map<String,Object> fields = new HashMap<String,Object>();
        fields.put(SEE_KEY, seeArray);
        try {
            channel.send(null, fields, true);
        } catch (TelehashException e) {
            return;
        }
    }

    private void handlePeer(Channel channel, ChannelPacket channelPacket) {
        String peerString = (String) channelPacket.get(PEER_KEY);
        if (peerString == null || peerString.isEmpty()) {
            return;
        }
        HashName target = new HashName(Util.hexToBytes(peerString));
        Line line = mTelehash.getSwitch().getLineManager().getLineByHashName(target);
        if (line == null) {
            // no line to the target
            return;
        }
        if (line.getRemotePeerNode() == null) {
            Log.e("Line "+line+" has no PeerNode.  has: "+line.getRemoteNode());
            Log.e("node tracker has: "+mNodeTracker.getClosestNodes(target, 1));
        }

        PeerNode originatingNode = channel.getRemoteNode();
        if (! (originatingNode.getPath() instanceof InetPath)) {
            return;
        }
        InetPath path = (InetPath)originatingNode.getPath();
        if (originatingNode.getFingerprints() == null) {
            Log.e("peer originator has no fingerprints: "+originatingNode.getHashName());
            return;
        }

        // cipher set matchmaking
        CipherSetIdentifier csid =
                PeerNode.bestCipherSet(originatingNode, line.getRemotePeerNode());
        if (csid == null) {
            Log.e("error while matching cipher sets", new TelehashException("null csid"));
            return;
        }
        if (! channel.getRemoteNode().getActiveCipherSetIdentifier().equals(csid)) {
            Log.e("cipher set mismatch");
            return;
        }

        // send a connect message to the target with the originator's information
        Channel newChannel = line.openChannel(CONNECT_TYPE, null);
        List<JSONObject> paths = new ArrayList<JSONObject>(1);
        paths.add(path.toJSONObject());
        Map<String,Object> fields = new HashMap<String,Object>();
        fields.put("from", originatingNode.getFingerprints().toJSON());
        fields.put("paths", paths);
        try {
            newChannel.send(
                    channel.getRemoteNode().getActivePublicKey().getEncoded(),
                    fields,
                    true
            );
        } catch (TelehashException e) {
            // best-effort only
            e.printStackTrace();
        }
    }

    private void handleConnect(
            Channel channel,
            ChannelPacket channelPacket
    ) throws TelehashException {
        // parse the paths
        Object pathsObject = channelPacket.get("paths");
        if (! (pathsObject instanceof JSONArray)) {
            return;
        }
        List<Path> paths = Path.parsePathArray((JSONArray)pathsObject);
        if (paths == null || paths.isEmpty()) {
            return;
        }

        // TODO: support more than the first path
        Path path = paths.get(0);

        // cipher set matchmaking
        Object fromObject = channelPacket.get("from");
        if (fromObject == null) {
            throw new TelehashException("connect packet is missing from!");
        }
        if (! (fromObject instanceof JSONObject)) {
            throw new TelehashException("expected JSONObject!");
        }
        FingerprintSet fingerprints = new FingerprintSet((JSONObject)fromObject);
        CipherSetIdentifier csid = FingerprintSet.bestCipherSet(
                mTelehash.getLocalNode().getFingerprints(),
                fingerprints
        );
        if (csid == null) {
            Log.e("error while matching cipher sets", new TelehashException("null csid"));
        }

        // extract the peer's public key
        byte[] body = channelPacket.getBody();
        if (body == null) {
            return;
        }
        CipherSet cipherSet = mTelehash.getCrypto().getCipherSet(csid);
        if (cipherSet == null) {
            throw new TelehashException("unknown cipher set id in connect");
        }
        HashNamePublicKey publicKey = cipherSet.decodeHashNamePublicKey(body);

        PeerNode node = new PeerNode(
                fingerprints.getHashName(), csid, publicKey, Collections.singleton(path)
        );
        node.updateFingerprints(fingerprints);
        mTelehash.getSwitch().getLineManager().openLine(node, false, null, null);
    }

    /**
     * Initiate a node lookup of the specified hashname. The caller should
     * maintain a reference to the returned NodeLookupTask until it is no longer
     * needed, to avoid early garbage collection.
     *
     * @param hashName
     * @param handler
     * @return
     */
    public NodeLookupTask nodeLookup(HashName hashName, NodeLookupTask.Handler handler) {
        NodeLookupTask lookup = new NodeLookupTask(mTelehash, mNodeTracker, hashName, handler);
        lookup.start();
        return lookup;
    }
}