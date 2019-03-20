/**
 * Copyright 2018 Hewlett Packard Enterprise Development LP
 */
package com.nimblestorage.npm.agent.resource;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public class SslUtil {
    private static final String RSA = "RSA";
    private static final String BC = "BC";
    private static final String JCEKS = "JCEKS";

    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    static {
        // prevents NoSuchProviderException:no such provider: BC
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Generate a key pair and certificate and add it to the keystore specified.
     * @param cn X.500 distinguished name (CN=)
     * @param subjectAltNames ip addresses
     * @param alias keystore alias
     * @param keystoreFilename keystore filename
     * @param passwd keystore password
     */
    public static void generateAndStoreKeyAndCertificate(String cn, List<GeneralName> subjectAltNames, String alias, String keystoreFilename, char[] passwd) throws IOException, Exception {
        KeyPairGenerator keyPairGenerator = null;
        try {
            keyPairGenerator = KeyPairGenerator.getInstance(RSA, BC);
        } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new Exception("Error creating key pair.", e);
        }
        keyPairGenerator.initialize(1024, new SecureRandom());
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        X500Principal principal = new X500Principal("CN=" + cn);

        X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
                principal,
                BigInteger.valueOf(1),
                new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)),
                new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650)),
                principal,
                keyPair.getPublic());

        if (subjectAltNames != null && !subjectAltNames.isEmpty()) {
            GeneralNames sans = new GeneralNames(subjectAltNames.toArray(new GeneralName[] {}));
            certificateBuilder.addExtension(Extension.subjectAlternativeName, false, sans);
        }

        ContentSigner signer = null;
        try {
            signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC).build(keyPair.getPrivate());
            X509Certificate selfCert = new JcaX509CertificateConverter().setProvider(BC).getCertificate(certificateBuilder.build(signer));

            KeyStore keyStore = getKeystoreFromFile(keystoreFilename, passwd);
            keyStore.setKeyEntry(alias, keyPair.getPrivate(), passwd, new Certificate[] { selfCert });
            writeKeystore(keyStore, keystoreFilename, passwd);
        } catch (OperatorCreationException | CertificateException | KeyStoreException e) {
            throw new Exception("Error creating certificate.", e);
        }
    }

    public static KeyStore getKeystoreFromFile(String filename, char[] passwd) throws Exception {
        KeyStore keyStore = null;
        if (!Files.exists(Paths.get(filename))) {
            try {
                keyStore = KeyStore.getInstance(JCEKS);
                keyStore.load(null, passwd);
            } catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException e) {
                throw new Exception("Error opening keystore.", e);
            }
        } else {
            try (InputStream fis = new FileInputStream(filename);) {
                keyStore = KeyStore.getInstance(JCEKS);
                keyStore.load(fis, passwd);
            } catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException e) {
                throw new Exception("Error opening keystore.", e);
            }
        }

        return keyStore;
    }

    public static void writeKeystore(KeyStore keyStore, String filename, char[] passwd) throws Exception {
        try (OutputStream fos = new FileOutputStream(filename)) {
            keyStore.store(fos, passwd);
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
            throw new Exception("Error closing keystore.", e);
        }
    }

}

