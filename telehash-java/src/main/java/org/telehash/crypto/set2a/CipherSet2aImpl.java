package org.telehash.crypto.set2a;

import org.spongycastle.crypto.AsymmetricCipherKeyPair;
import org.spongycastle.crypto.CryptoException;
import org.spongycastle.crypto.agreement.ECDHBasicAgreement;
import org.spongycastle.crypto.engines.AESEngine;
import org.spongycastle.crypto.generators.ECKeyPairGenerator;
import org.spongycastle.crypto.generators.RSAKeyPairGenerator;
import org.spongycastle.crypto.params.AEADParameters;
import org.spongycastle.crypto.params.AsymmetricKeyParameter;
import org.spongycastle.crypto.params.ECDomainParameters;
import org.spongycastle.crypto.params.ECKeyGenerationParameters;
import org.spongycastle.crypto.params.ECPrivateKeyParameters;
import org.spongycastle.crypto.params.ECPublicKeyParameters;
import org.spongycastle.crypto.params.KeyParameter;
import org.spongycastle.crypto.params.RSAKeyGenerationParameters;
import org.spongycastle.crypto.params.RSAPrivateCrtKeyParameters;
import org.spongycastle.jce.ECNamedCurveTable;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.jce.spec.ECNamedCurveParameterSpec;
import org.spongycastle.util.BigIntegers;
import org.telehash.core.CipherSetIdentifier;
import org.telehash.core.FingerprintSet;
import org.telehash.core.HashName;
import org.telehash.core.Line;
import org.telehash.core.LocalNode;
import org.telehash.core.OpenPacket;
import org.telehash.core.Packet;
import org.telehash.core.Packet.SplitPacket;
import org.telehash.core.PeerNode;
import org.telehash.core.Telehash;
import org.telehash.core.TelehashException;
import org.telehash.core.Util;
import org.telehash.crypto.CipherSet;
import org.telehash.crypto.Crypto;
import org.telehash.crypto.HashNameKeyPair;
import org.telehash.crypto.HashNamePrivateKey;
import org.telehash.crypto.HashNamePublicKey;
import org.telehash.crypto.LineKeyPair;
import org.telehash.crypto.LinePrivateKey;
import org.telehash.crypto.LinePublicKey;
import org.telehash.network.Path;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Collections;

public class CipherSet2aImpl implements CipherSet {
    private static final byte[] FIXED_IV = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1};
    private static final int OPEN_INNER_MAC_BITS = 128;
    private static final int OPEN_SIGNATURE_MAC_BITS = 32;
    private static final int LINE_CHANNEL_MAC_BITS = 128;
    private static final int LINE_KEY_CIPHERTEXT_BYTES = 256;
    private static final int SIGNATURE_CIPHERTEXT_BYTES = 256 + 4; // includes MAC
    private static final int OPEN_HEADER_BYTES = 3;
    private static final int LINE_IV_SIZE = 16;

    private static final String RSA_PRIVATE_KEY_PEM_TYPE = "RSA PRIVATE KEY";
    private static final String RSA_PUBLIC_KEY_PEM_TYPE = "PUBLIC KEY";

    public static final CipherSetIdentifier CIPHER_SET_ID = new CipherSetIdentifier(0x2a);

    private Crypto mCrypto;

    // elliptic curve settings
    private ECNamedCurveParameterSpec mECNamedCurveParameterSpec;
    private ECKeyPairGenerator mECGenerator = new ECKeyPairGenerator();
    private ECDomainParameters mECDomainParameters;
    private ECKeyGenerationParameters mECKeyGenerationParameters;

    private SecureRandom mRandom = new SecureRandom();

    static {
        Security.addProvider(new BouncyCastleProvider());
    };

    public CipherSet2aImpl(Crypto crypto) {
        mCrypto = crypto;

        // initialize elliptic curve parameters and generator
        mECNamedCurveParameterSpec =
                ECNamedCurveTable.getParameterSpec("prime256v1");
        mECGenerator = new ECKeyPairGenerator();
        mECDomainParameters =
                new ECDomainParameters(
                        mECNamedCurveParameterSpec.getCurve(),
                        mECNamedCurveParameterSpec.getG(),
                        mECNamedCurveParameterSpec.getN()
                );
        mECKeyGenerationParameters =
                new ECKeyGenerationParameters(mECDomainParameters, mRandom);
        mECGenerator.init(mECKeyGenerationParameters);
    }

    /**
     * Return the Cipher Set ID (CSID) for this cipher set.
     */
    @Override
    public CipherSetIdentifier getCipherSetId() {
        return CIPHER_SET_ID;
    }

    /**
     * Generate a fresh hashname key pair for a newly provisioned Telehash node.
     *
     * @return The new hashname key pair.
     * @throws TelehashException
     */
    @Override
    public HashNameKeyPair generateHashNameKeyPair() throws TelehashException {
        // generate a 2048 bit key pair
        RSAKeyPairGenerator generator = new RSAKeyPairGenerator();
        generator.init(
                new RSAKeyGenerationParameters(
                    new BigInteger("10001", 16),//publicExponent
                    // TODO: see warning in getRandomBytes()!
                    new SecureRandom(),
                    2048, // key length
                    80    // prime certainty
                )
            );
        AsymmetricCipherKeyPair keyPair = generator.generateKeyPair();

        AsymmetricKeyParameter publicKey = keyPair.getPublic();
        AsymmetricKeyParameter privateKey = keyPair.getPrivate();
        if (! (privateKey instanceof RSAPrivateCrtKeyParameters)) {
            throw new TelehashException("generated key is not an RSA private key.");
        }
        return new HashNameKeyPairImpl(
                new HashNamePublicKeyImpl(publicKey),
                new HashNamePrivateKeyImpl((RSAPrivateCrtKeyParameters)privateKey)
        );
    }

    /**
     * Create a new HashNameKeyPair from the provided public and private key.
     * @param privateKey
     * @param publicKey
     * @return The newly created HashNameKeyPair object.
     */
    @Override
    public HashNameKeyPair createHashNameKeyPair(
            HashNamePublicKey publicKey,
            HashNamePrivateKey privateKey
    ) {
        return new HashNameKeyPairImpl(
                (HashNamePublicKeyImpl)publicKey,
                (HashNamePrivateKeyImpl)privateKey
        );
    }

    /**
     * Decode a public key.
     *
     * @param buffer The byte buffer containing the encoded key.
     * @return The decoded public key.
     * @throws TelehashException If the buffer cannot be parsed.
     */
    @Override
    public HashNamePublicKey decodeHashNamePublicKey(byte[] buffer) throws TelehashException {
        return new HashNamePublicKeyImpl(buffer);
    }

    /**
     * Decode a private key.
     *
     * @param buffer The byte buffer containing the encoded key.
     * @return The decoded private key.
     * @throws TelehashException If the buffer cannot be parsed.
     */
    @Override
    public HashNamePrivateKey decodeHashNamePrivateKey(byte[] buffer) throws TelehashException {
        return new HashNamePrivateKeyImpl(buffer);
    }

    /**
     * Decode an ANSI X9.63-encoded elliptic curve public key.
     *
     * @param buffer The byte buffer containing the ANSI X9.63-encoded key.
     * @return The decoded public key.
     * @throws TelehashException If the ANSI X9.63 buffer cannot be parsed.
     */
    @Override
    public LinePublicKey decodeLinePublicKey(byte[] buffer) throws TelehashException {
        return new LinePublicKeyImpl(buffer, mECDomainParameters);
    }

    /**
     * Decode a byte-encoded elliptic curve private key.
     *
     * @param buffer The byte buffer containing the encoded key.
     * @return The decoded private key.
     * @throws TelehashException If the byte buffer cannot be parsed.
     */
    @Override
    public LinePrivateKey decodeLinePrivateKey(byte[] buffer) throws TelehashException {
        return new LinePrivateKeyImpl(buffer, mECDomainParameters);
    }

    /**
     * Create a new elliptic curve key pair from the provided public and private key.
     * @param privateKey
     * @param publicKey
     * @return The newly created key pair.
     */
    @Override
    public LineKeyPair createLineKeyPair(
            LinePublicKey publicKey,
            LinePrivateKey privateKey
    ) throws TelehashException {
        return new LineKeyPairImpl(publicKey, privateKey);
    }

    /**
     * Generate a fresh elliptic curve key pair
     */
    @Override
    public LineKeyPair generateLineKeyPair() throws TelehashException {
        AsymmetricCipherKeyPair keyPair = mECGenerator.generateKeyPair();

        AsymmetricKeyParameter publicKey = keyPair.getPublic();
        AsymmetricKeyParameter privateKey = keyPair.getPrivate();
        if (! (publicKey instanceof ECPublicKeyParameters)) {
            throw new TelehashException("generated key is not an elliptic curve public key.");
        }
        if (! (privateKey instanceof ECPrivateKeyParameters)) {
            throw new TelehashException("generated key is not an elliptic curve private key.");
        }
        return new LineKeyPairImpl(
                (ECPublicKeyParameters)publicKey,
                (ECPrivateKeyParameters)privateKey
        );
    }

    /**
     * Encrypt the provided plaintext using AES-256-GCM with the provided
     * initialization vector (IV) and key.
     *
     * @param plainText
     *            The plaintext to encrypt.
     * @param iv
     *            The initialization vector.
     * @param key
     *            The encryption key.
     * @return The resulting ciphertext.
     * @throws TelehashException
     *             If a problem occurred.
     */
    private byte[] encryptAES256GCM(
            byte[] plainText,
            byte[] iv,
            byte[] key,
            int tagBits
    ) throws TelehashException {
        // initialize cipher
        GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());
        AEADParameters params = new AEADParameters(new KeyParameter(key), tagBits, iv);
        cipher.init(true,  params);

        // encrypt
        byte[] cipherText = new byte[cipher.getOutputSize(plainText.length)];
        int nbytes = cipher.processBytes(plainText, 0, plainText.length, cipherText, 0);
        try {
            nbytes += cipher.doFinal(cipherText, nbytes);
        } catch (CryptoException e) {
            throw new TelehashException(e);
        }

        // trim output if needed
        if (nbytes < cipherText.length) {
            byte[] trimmedCipherText = new byte[nbytes];
            System.arraycopy(cipherText, 0, trimmedCipherText, 0, nbytes);
            cipherText = trimmedCipherText;
        }

        return cipherText;
    }

    /**
     * Decrypt the provided ciphertext using AES-256-GCM with the provided
     * initialization vector (IV) and key.
     *
     * @param plainText The ciphertext to decrypt.
     * @param iv The initialization vector.
     * @param key The encryption key.
     * @return The resulting plaintext.
     * @throws TelehashException If a problem occurred.
     */
    private byte[] decryptAES256GCM(
            byte[] cipherText,
            byte[] iv,
            byte[] key,
            int tagBits
    ) throws TelehashException {
        // initialize cipher
        GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());
        AEADParameters params = new AEADParameters(new KeyParameter(key), tagBits, iv);
        cipher.init(false,  params);

        // decrypt
        byte[] plainText = new byte[cipher.getOutputSize(cipherText.length)];
        int nbytes = cipher.processBytes(cipherText, 0, cipherText.length, plainText, 0);
        try {
            nbytes += cipher.doFinal(plainText, nbytes);
        } catch (CryptoException e) {
            throw new TelehashException(e);
        }

        // trim output if needed
        if (nbytes < plainText.length) {
            byte[] trimmedPlainText = new byte[nbytes];
            System.arraycopy(plainText, 0, trimmedPlainText, 0, nbytes);
            plainText = trimmedPlainText;
        }

        return plainText;
    }

    @Override
    public OpenPacket parseOpenPacket(
            Telehash telehash,
            SplitPacket splitPacket,
            Path path
    ) throws TelehashException {
        CipherSetIdentifier csid = new CipherSetIdentifier(splitPacket.singleByteHeader);
        if (splitPacket.json != null || (! csid.equals(CIPHER_SET_ID))) {
            throw new TelehashException("invalid open packet");
        }
        if (splitPacket.body.length <
                LINE_KEY_CIPHERTEXT_BYTES+SIGNATURE_CIPHERTEXT_BYTES) {
            throw new TelehashException("open packet is too small.");
        }

        // extract the outer packet fields
        // TODO: optimize
        byte[] lineKeyCiphertext = new byte[LINE_KEY_CIPHERTEXT_BYTES];
        System.arraycopy(splitPacket.body, 0, lineKeyCiphertext, 0, LINE_KEY_CIPHERTEXT_BYTES);
        byte[] signatureCiphertext = new byte[SIGNATURE_CIPHERTEXT_BYTES];
        System.arraycopy(
                splitPacket.body, LINE_KEY_CIPHERTEXT_BYTES,
                signatureCiphertext, 0,
                SIGNATURE_CIPHERTEXT_BYTES
        );
        byte[] innerPacketCiphertext = new byte[
                splitPacket.body.length -
                LINE_KEY_CIPHERTEXT_BYTES -
                SIGNATURE_CIPHERTEXT_BYTES
        ];
        System.arraycopy(
                splitPacket.body, LINE_KEY_CIPHERTEXT_BYTES+SIGNATURE_CIPHERTEXT_BYTES,
                innerPacketCiphertext, 0,
                innerPacketCiphertext.length
        );

        // Using your hashname private key, decrypt the line public key of the sender.
        byte[] linePublicKeyBuffer = mCrypto.decryptRSAOAEP(
                telehash.getLocalNode().getPrivateKey(CIPHER_SET_ID),
                lineKeyCiphertext
        );
        LinePublicKey linePublicKey = decodeLinePublicKey(linePublicKeyBuffer);

        // Hash the ECC public key with SHA-256 to generate the AES key
        byte[] innerPacketKey =
                mCrypto.sha256Digest(linePublicKeyBuffer);

        // Decrypt the inner packet using the generated key and IV value with
        // the AES-256-CTR algorithm.
        byte[] innerPacketBuffer = decryptAES256GCM(
                innerPacketCiphertext,
                FIXED_IV,
                innerPacketKey,
                OPEN_INNER_MAC_BITS
        );

        // extract required JSON values from the inner packet
        SplitPacket innerPacket = Packet.splitPacket(innerPacketBuffer);
        OpenPacket.Inner innerHead = OpenPacket.Inner.deserialize(innerPacket);

        // Verify the "to" value of the inner packet matches your hashname
        if (! innerHead.mDestination.equals(telehash.getLocalNode().getHashName())) {
            throw new TelehashException("received packet not destined for the local node.");
        }

        // Extract the hashname public key of the sender from the inner
        // packet BODY (binary encoded format).
        HashNamePublicKey senderHashNamePublicKey =
                decodeHashNamePublicKey(innerPacket.body);

        // derive the sender's hashname and create a node object
        //Node sourceNode = new Node(senderHashNamePublicKey, path);
        FingerprintSet senderFingerprints = new FingerprintSet(innerHead.mFrom);
        HashName senderHashName = senderFingerprints.getHashName();
        PeerNode sourceNode = new PeerNode(
                senderHashName, csid, senderHashNamePublicKey, Collections.singleton(path)
        );

        sourceNode.updateFingerprints(senderFingerprints);

        // Verify the "at" timestamp is newer than any other "open"
        // requests received from the sender.
        // TODO: "newer than any other open..." <-- should be handled at higher level

        // SHA-256 hash the ECC public key with the 16 bytes derived from the
        // inner line hex value to generate a new AES key
        byte[] signatureKey = mCrypto.sha256Digest(
                Util.concatenateByteArrays(
                        linePublicKeyBuffer,
                        innerHead.mLineIdentifier.getBytes()
                )
        );

        // Decrypt the outer packet sig value using AES-256-CTR with the key
        // from #8 and the same IV value as #3.
        byte[] signature = decryptAES256GCM(
                signatureCiphertext,
                FIXED_IV,
                signatureKey,
                OPEN_SIGNATURE_MAC_BITS
        );

        // Using the hashname public key of the sender, verify the signature
        // (decrypted in #9) of the original (encrypted) form of the inner
        // packet
        if (! mCrypto.verifyRSA(
                senderHashNamePublicKey,
                innerPacketCiphertext,
                signature)) {
            throw new TelehashException("signature verification failed.");
        }

        // If an open packet has not already been sent to this hashname, do so
        // by creating one following the steps above
        // TODO: handle at higher level

        // After sending your own open packet in response, you may now generate
        // a line shared secret using the received and sent line public keys and
        // Elliptic Curve Diffie-Hellman (ECDH).
        // TODO: handle at higher level

        return new OpenPacket(
            CIPHER_SET_ID,
            sourceNode,
            linePublicKey,
            innerHead.mOpenTime,
            innerHead.mLineIdentifier
        );
    }

    /**
     * Pre-render the open packet.
     *
     * @throws TelehashException
     */
    @Override
    public void preRenderOpenPacket(OpenPacket open) throws TelehashException {
        Crypto crypto = Telehash.get().getCrypto();

        // note the current time.
        // This is a "local" timestamp -- the remote node will not
        // interpret this as the number of milliseconds since 1970,
        // but merely as an ever-incrementing value where a greater
        // value indicates a newer open packet.  This timestamp should
        // be the time of line key generation.
        open.setOpenTime(System.currentTimeMillis());

        // generate the line key pair
        LineKeyPair lineKeyPair = generateLineKeyPair();
        open.setLinePublicKey(lineKeyPair.getPublicKey());
        open.setLinePrivateKey(lineKeyPair.getPrivateKey());

        // generate KEYC (the line key ciphertext) by encrypting the
        // public line key with the recipient's hashname public key.
        if (! open.getDestinationNode().getActiveCipherSetIdentifier().equals(CIPHER_SET_ID)) {
            throw new TelehashException("cipher set mismatch");
        }
        byte[] lineKeyCiphertext = crypto.encryptRSAOAEP(
                open.getDestinationNode().getActivePublicKey(),
                open.getLinePublicKey().getEncoded()
        );
        if (lineKeyCiphertext.length != LINE_KEY_CIPHERTEXT_BYTES) {
            throw new TelehashException("generated line key ciphertext is invalid.");
        }
        open.setPreRenderedLineKeyCiphertext(lineKeyCiphertext);
    }

    /**
     * Render the open packet into its final form.
     *
     * This version of the method allows the caller to pass in values for
     * certain otherwise calculated fields, allowing for deterministic open
     * packet creation suitable for unit tests.
     *
     * @param open The open packet object.
     * @param lineKeyCiphertext
     *            The line key ciphertext -- the public line key encrypted
     *            with the recipient's hashname public key.
     * @return The rendered open packet as a byte array.
     * @throws TelehashException
     */
    @Override
    public byte[] renderOpenPacket(
            OpenPacket open,
            LocalNode localNode,
            byte[] lineKeyCiphertext
    ) throws TelehashException {
        byte[] encodedLinePublicKey = open.getLinePublicKey().getEncoded();

        // SHA-256 hash the public line key to form the encryption
        // key for the inner packet
        byte[] innerPacketAESKey = mCrypto.sha256Digest(encodedLinePublicKey);

        // Form the inner packet containing a current timestamp "at", line
        // identifier, recipient hashname, and family (if you have such a
        // value). Your own hashname public key is the packet BODY in
        // the encoded binary format.
        OpenPacket.Inner innerHead = new OpenPacket.Inner(
                open.getDestinationNode().getHashName(),
                open.getOpenTime(),
                open.getLineIdentifier(),
                localNode.getFingerprints()
        );
        byte[] innerPacket = innerHead.serialize();
        innerPacket = Util.concatenateByteArrays(
                new byte[] {
                        (byte)((innerPacket.length >> 8) & 0xFF),
                        (byte)(innerPacket.length & 0xFF)
                },
                innerPacket,
                localNode.getPublicKey(CIPHER_SET_ID).getEncoded()
        );

        // Encrypt the inner packet using the hashed line public key from #4
        // and the IV you generated at #2 using AES-256-CTR.
        byte[] innerPacketCiphertext =
                encryptAES256GCM(innerPacket, FIXED_IV, innerPacketAESKey, OPEN_INNER_MAC_BITS);

        // Create a signature from the encrypted inner packet using your own hashname
        // keypair, a SHA 256 digest, and PKCSv1.5 padding
        byte[] signature = mCrypto.signRSA(
                localNode.getPrivateKey(CIPHER_SET_ID),
                innerPacketCiphertext
        );

        // Encrypt the signature using a new AES-256-CTR cipher with the same IV
        // and a new SHA-256 key hashed from the line public key + the
        // line value (16 bytes from #5), then base64 encode the result as the
        // value for the sig param.
        byte[] signatureKey = mCrypto.sha256Digest(
                Util.concatenateByteArrays(
                        encodedLinePublicKey,
                        open.getLineIdentifier().getBytes()
                )
        );
        byte[] signatureCiphertext =
                encryptAES256GCM(signature, FIXED_IV, signatureKey, OPEN_SIGNATURE_MAC_BITS);
        if (signatureCiphertext.length != SIGNATURE_CIPHERTEXT_BYTES) {
            throw new TelehashException("generated signature ciphertext is invalid.");
        }

        // assemble the outer packet
        byte[] openHeader = new byte[OPEN_HEADER_BYTES];
        openHeader[0] = 0x00;
        openHeader[1] = 0x01;
        openHeader[2] = CIPHER_SET_ID.getByte();
        byte[] buffer = Util.concatenateByteArrays(
                openHeader, lineKeyCiphertext, signatureCiphertext, innerPacketCiphertext
        );

        return buffer;
    }

    /**
     * Render the "inner" (i.e. cipherset-dependent) portion of a line packet.
     *
     * @param line The line associated with this line packet.
     * @param channelPlaintext The channel plaintext to encrypt and include.
     * @return The binary form of the inner packet.
     * @throws TelehashException
     */
    @Override
    public byte[] renderLineInnerPacket(
            Line line,
            byte[] channelPlaintext
    ) throws TelehashException {
        Crypto crypto = Telehash.get().getCrypto();

        // generate a random IV
        byte[] iv = crypto.getRandomBytes(LINE_IV_SIZE);
        if (iv.length != LINE_IV_SIZE) {
            throw new TelehashException("line iv must be exactly 16 bytes");
        }

        // encrypt the channel packet
        byte[] channelCiphertext = encryptAES256GCM(
                channelPlaintext,
                iv,
                line.getEncryptionKey(),
                LINE_CHANNEL_MAC_BITS
        );

        // assemble the inner (i.e. cipherset-generated) portion
        byte[] inner = Util.concatenateByteArrays(iv, channelCiphertext);

        return inner;
    }

    /**
     * Parse the "inner" (i.e. cipherset-dependent) portion of a line packet.
     *
     * @param line The line associated with this line packet.
     * @param innerPacket The binary form of the inner packet.
     * @return The channel packet plaintext.
     * @throws TelehashException
     */
    @Override
    public byte[] parseLineInnerPacket(Line line, byte[] innerPacket) throws TelehashException {
        Crypto crypto = Telehash.get().getCrypto();

        // extract iv
        if (innerPacket.length < LINE_IV_SIZE) {
            throw new TelehashException("invalid line packet");
        }
        byte[] iv = new byte[LINE_IV_SIZE];
        System.arraycopy(innerPacket, 0, iv, 0, LINE_IV_SIZE);

        // extract channel ciphertext
        int channelCiphertextSize = innerPacket.length - LINE_IV_SIZE;
        byte[] channelCiphertext = new byte[channelCiphertextSize];
        System.arraycopy(innerPacket, LINE_IV_SIZE, channelCiphertext, 0, channelCiphertextSize);

        // decrypt the channel ciphertext
        byte[] channelPlaintext = decryptAES256GCM(
                channelCiphertext,
                iv,
                line.getDecryptionKey(),
                LINE_CHANNEL_MAC_BITS
        );

        return channelPlaintext;
    }

    /**
     * Perform Elliptic Curve Diffie-Hellman key agreement
     *
     * @param remotePublicKey The EC public key of the remote node.
     * @param localPrivateKey The EC private key of the local node.
     * @return A byte array containing the shared secret.
     */
    @Override
    public byte[] calculateECDHSharedSecret(
            LinePublicKey remotePublicKey,
            LinePrivateKey localPrivateKey
    ) {
        ECDHBasicAgreement agreement = new ECDHBasicAgreement();
        agreement.init(((LinePrivateKeyImpl)localPrivateKey).getKey());
        BigInteger secretInteger =
                agreement.calculateAgreement(((LinePublicKeyImpl)remotePublicKey).getKey());
        byte[] secretBytes = BigIntegers.asUnsignedByteArray(32, secretInteger);
        return secretBytes;
    }

    /**
     * Parse a PEM-formatted RSA public key
     *
     * @param pem The PEM string.
     * @return The key.
     * @throws TelehashException If a problem occurs while reading the file.
     */
    @Override
    public HashNamePublicKey parseHashNamePublicKeyFromStorage(
            String string
    ) throws TelehashException {
        return RSAUtils.parseRSAPublicKeyFromStorage(string);
    }

    /**
     * Read a PEM-formatted RSA public key from a file.
     *
     * @param filename The filename of the file containing the PEM-formatted key.
     * @return The key.
     * @throws TelehashException If a problem occurs while reading the file.
     */
    @Override
    public HashNamePublicKey readHashNamePublicKeyFromFile(
            String filename
    ) throws TelehashException {
        return RSAUtils.readRSAPublicKeyFromFile(filename);
    }

    /**
     * Read a PEM-formatted RSA private key from a file.
     *
     * @param filename The filename of the file containing the PEM-formatted key.
     * @return The key.
     * @throws TelehashException If a problem occurs while reading the file.
     */
    @Override
    public HashNamePrivateKey readHashNamePrivateKeyFromFile(
            String filename
    ) throws TelehashException {
        return RSAUtils.readRSAPrivateKeyFromFile(filename);
    }

    /**
     * Write a PEM-formatted RSA public key to a file.
     *
     * @param filename The filename of the file to write.
     * @param key The key to write.
     * @throws IOException If a problem occurs while reading the file.
     */
    @Override
    public void writeHashNamePublicKeyToFile(
            String filename,
            HashNamePublicKey key
    ) throws TelehashException {
        RSAUtils.writeRSAPublicKeyToFile(filename, key);
    }

    /**
     * Write a PEM-formatted RSA private key to a file.
     *
     * @param filename The filename of the file to write.
     * @param key The key to write.
     * @throws IOException If a problem occurs while reading the file.
     */
    @Override
    public void writeHashNamePrivateKeyToFile(
            String filename,
            HashNamePrivateKey key
    ) throws TelehashException {
        RSAUtils.writeRSAPrivateKeyToFile(filename, key);
    }
}
