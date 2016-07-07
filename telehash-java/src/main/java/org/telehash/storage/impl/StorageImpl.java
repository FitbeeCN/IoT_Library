package org.telehash.storage.impl;

import org.telehash.json.JSONArray;
import org.telehash.json.JSONException;
import org.telehash.json.JSONObject;
import org.telehash.json.JSONTokener;
import org.telehash.core.CipherSetIdentifier;
import org.telehash.core.FingerprintSet;
import org.telehash.core.HashName;
import org.telehash.core.LocalNode;
import org.telehash.core.Log;
import org.telehash.core.SeedNode;
import org.telehash.core.Telehash;
import org.telehash.core.TelehashException;
import org.telehash.core.Util;
import org.telehash.crypto.CipherSet;
import org.telehash.crypto.Crypto;
import org.telehash.crypto.HashNameKeyPair;
import org.telehash.crypto.HashNamePrivateKey;
import org.telehash.crypto.HashNamePublicKey;
import org.telehash.network.Path;
import org.telehash.storage.Storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * This class contains implementations for the storage functions needed by
 * Telehash.
 */
public class StorageImpl implements Storage {

    private static final String DEFAULT_LOCALNODE_FILENAME_BASE = "telehash-localnode";
    private static final String PRIVATE_KEY_FILENAME_SUFFIX = ".key";
    private static final String PUBLIC_KEY_FILENAME_SUFFIX = ".pub";
    private static final String PATHS_KEY = "paths";

    /**
     * Read the local Telehash node keys from files named using the
     * specified base filename.
     *
     * @param localNodeBaseFilename
     *            The base filename, e.g. "localnode".
     * @return The read and parsed Telehash local node.
     * @throws TelehashException
     *             If a problem happened while reading and parsing the local node.
     */
    @Override
    public LocalNode readLocalNode(String localNodeBaseFilename) throws TelehashException {
        SortedMap<CipherSetIdentifier,HashNameKeyPair> keyPairs =
                new TreeMap<CipherSetIdentifier,HashNameKeyPair>();
        for (CipherSet cipherSet : Telehash.get().getCrypto().getAllCipherSets()) {
            CipherSetIdentifier csid = cipherSet.getCipherSetId();
            String csidExtension = "." + csid.asHex();
            String publicKeyFilename =
                    localNodeBaseFilename + csidExtension + PUBLIC_KEY_FILENAME_SUFFIX;
            String privateKeyFilename =
                    localNodeBaseFilename + csidExtension + PRIVATE_KEY_FILENAME_SUFFIX;
            if (new File(publicKeyFilename).exists() && new File(privateKeyFilename).exists()) {
                Log.i("keys exist for cipher set: "+cipherSet);

                // read keys
                HashNamePublicKey publicKey =
                        cipherSet.readHashNamePublicKeyFromFile(publicKeyFilename);
                HashNamePrivateKey privateKey =
                        cipherSet.readHashNamePrivateKeyFromFile(privateKeyFilename);
                keyPairs.put(
                        csid,
                        cipherSet.createHashNameKeyPair(publicKey, privateKey)
                );
            }
        }
        if (keyPairs.isEmpty()) {
            throw new TelehashException(
                "no key pairs found for local node: "+localNodeBaseFilename,
                new FileNotFoundException()
            );
        }
        return new LocalNode(keyPairs);
    }

    /**
     * Read the local Telehash local node keys from files named using
     * the default base filename.
     *
     * @return The read and parsed Telehash local node.
     * @throws TelehashException
     *             If a problem happened while reading and parsing the local node.
     */
    @Override
    public LocalNode readLocalNode() throws TelehashException {
        return readLocalNode(DEFAULT_LOCALNODE_FILENAME_BASE);
    }

    /**
     * Write the local Telehash local node keys into files named using
     * the specified base filename.
     *
     * @param localNode
     *            The local node to write.
     * @param localNodeBaseFilename
     *            The base filename, e.g. "localnode".
     * @throws TelehashException
     *             If a problem happened while writing the local node.
     */
    @Override
    public void writeLocalNode(LocalNode localNode, String localNodeBaseFilename)
            throws TelehashException {
        Crypto crypto = Telehash.get().getCrypto();
        for (Map.Entry<CipherSetIdentifier,HashNameKeyPair> entry :
                localNode.getHashNameKeyPairs().entrySet()) {
            CipherSetIdentifier csid = entry.getKey();
            HashNameKeyPair keyPair = entry.getValue();
            CipherSet cipherSet = crypto.getCipherSet(csid);
            String csidExtension = "." + csid.asHex();
            String publicKeyFilename =
                    localNodeBaseFilename + csidExtension + PUBLIC_KEY_FILENAME_SUFFIX;
            String privateKeyFilename =
                    localNodeBaseFilename + csidExtension + PRIVATE_KEY_FILENAME_SUFFIX;
            cipherSet.writeHashNamePublicKeyToFile(
                    publicKeyFilename,
                    keyPair.getPublicKey()
            );
            cipherSet.writeHashNamePrivateKeyToFile(
                    privateKeyFilename,
                    keyPair.getPrivateKey()
            );
        }
    }

    /**
     * Write the local Telehash local node keys into files named using
     * the default base filename.
     *
     * @param localNode
     *            The local node to write.
     * @throws TelehashException
     *             If a problem happened while writing the local node.
     */
    @Override
    public void writeLocalNode(LocalNode localNode) throws TelehashException {
        writeLocalNode(localNode, DEFAULT_LOCALNODE_FILENAME_BASE);
    }

    private static final String PUBLICKEYS_KEY = "keys";
    private static final String FINGERPRINTS_KEY = "parts";

    /**
     * Read the local seed cache to obtain a set of nodes that may be used to
     * bootstrap the switch onto the Telehash network.
     *
     * @param seedsFilename
     *            The filename of the JSON-encoded list of seed nodes.
     * @return A set of seed nodes.
     * @throws TelehashException
     *             If a problem happened while reading and parsing the seeds.
     */
    @Override
    public Set<SeedNode> readSeeds(String seedsFilename) throws TelehashException {
        Set<SeedNode> nodes = new HashSet<SeedNode>();

        JSONTokener tokener;
        try {
            tokener = new JSONTokener(new FileInputStream(seedsFilename));
        } catch (JSONException e) {
            throw new TelehashException(e);
        } catch (FileNotFoundException e) {
            throw new TelehashException(e);
        }

        JSONObject toplevel = new JSONObject(tokener);
        Iterator<?> toplevelIterator = toplevel.keys();
        while (toplevelIterator.hasNext()) {
            Object keyObject = toplevelIterator.next();
            if (keyObject == null || (! (keyObject instanceof String))) {
                Log.w("unknown json key object type: "+keyObject);
                continue;
            }
            String keyString = (String)keyObject;

            HashName hashName = new HashName(Util.hexToBytes(keyString));
            JSONObject seed = toplevel.getJSONObject(keyString);

            FingerprintSet fingerprints = new FingerprintSet(seed.getJSONObject(FINGERPRINTS_KEY));
            if (! fingerprints.getHashName().equals(hashName)) {
                throw new TelehashException("seed fingerprints do not match hashname");
            }

            // parse seed paths
            List<Path> paths = new ArrayList<Path>();
            if (seed.has(PATHS_KEY)) {
                JSONArray pathsArray = seed.getJSONArray(PATHS_KEY);
                paths.addAll(Path.parsePathArray(pathsArray));
            }
            if (paths.isEmpty()) {
                throw new TelehashException("no valid network paths found for seed!");
            }

            JSONObject keysObject = seed.getJSONObject(PUBLICKEYS_KEY);
            Iterator<?> keysIter = keysObject.keys();
            SortedMap<CipherSetIdentifier,HashNamePublicKey> publicKeys =
                    new TreeMap<CipherSetIdentifier,HashNamePublicKey>();
            while (keysIter.hasNext()) {
                // cipher set id
                Object csidObject = keysIter.next();
                if (! (csidObject instanceof String)) {
                    throw new TelehashException("invalid csid in seeds json");
                }
                String csidString = (String)csidObject;
                CipherSetIdentifier csid = new CipherSetIdentifier((String)csidObject);

                // key
                Object pubkeyObject = keysObject.get(csidString);
                if (! (pubkeyObject instanceof String)) {
                    throw new TelehashException("invalid key in seeds json for csid "+csid);
                }
                String pubkeyString = (String)pubkeyObject;
                byte[] pubkeyBuffer = Util.base64Decode(pubkeyString);
                CipherSet cipherSet = Telehash.get().getCrypto()
                        .getCipherSet(csid);
                if (cipherSet == null) {
                    Log.w("unknown cipher set in seeds json: "+csid);
                } else {
                    HashNamePublicKey publicKey = cipherSet.decodeHashNamePublicKey(pubkeyBuffer);

                    // confirm fingerprint
                    byte[] publicKeyFingerprint = publicKey.getFingerprint();
                    byte[] providedFingerprint = fingerprints.get(csid);
                    if (publicKeyFingerprint == null ||
                            providedFingerprint == null ||
                            (! Arrays.equals(publicKeyFingerprint,  providedFingerprint))) {
                        throw new TelehashException("seed pubkey does not match fingerprint");
                    }
                    publicKeys.put(csid, publicKey);
                }
            }

            SeedNode node = new SeedNode(fingerprints, publicKeys, paths);
            nodes.add(node);
        }
        return nodes;
    }
}
