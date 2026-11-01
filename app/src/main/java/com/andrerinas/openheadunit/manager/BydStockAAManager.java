package com.andrerinas.openheadunit.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BYD DiLink Orijinal Android Auto (com.byd.androidauto) Sistem Servisi Yöneticisi.
 *
 * BYD DiLink multimedya sisteminde (Android 10 / SDK 29) yerleşik Android Auto paketi,
 * telefon Bluetooth ile araca bağlandığı anda RFCOMM ve Wi-Fi Direct el sıkışmasını
 * ilk yakalayan sistem servisi olduğu için DiAuto'nun bağlantısını bloke eder.
 *
 * Kinex Launcher'ın çözüm mimarisinde olduğu gibi bu yönetici:
 * 1. Yerel ADB (localhost:5555 TCP socket) üzerinden Android Open Source Project protokolüyle
 *    "pm disable-user --user 0 com.byd.androidauto" komutunu çalıştırır.
 * 2. Paket durumunu /data/system/users/0/package-restrictions.xml dosyasına kalıcı olarak
 *    COMPONENT_ENABLED_STATE_DISABLED_USER şeklinde kaydeder.
 * 3. Araç yeniden başlatılsa bile com.byd.androidauto gizli kalır ve DiAuto kesintisiz bağlanır.
 * 4. Gerekli durumlarda "pm enable com.byd.androidauto" ile orijinal AA tekrar açılabilir.
 */
public class BydStockAAManager {

    private static final String TAG = "BydStockAAManager";
    public static final String TARGET_PACKAGE = "com.byd.androidauto";
    private static final String PREFS_NAME = "diauto_byd_aa_prefs";
    private static final String KEY_AUTO_DISABLE = "keep_stock_aa_disabled";

    private final Context context;
    private final AdbCommandExecutor adbExecutor;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isBusy = new AtomicBoolean(false);

    /**
     * Projedeki mevcut veya özel ADB / Shell yürütücüsünü bağlamak için arayüz.
     */
    public interface AdbCommandExecutor {
        boolean executeShellCommand(String command) throws Exception;
    }

    /**
     * Varsayılan çok katmanlı ADB yürütücüsü (Local ADB 127.0.0.1:5555 + Root/Shizuku + Shell fallback).
     */
    public BydStockAAManager(Context context) {
        this(context, null);
    }

    /**
     * Özel AdbCommandExecutor sağlandığında kullanılan yapıcı.
     */
    public BydStockAAManager(Context context, AdbCommandExecutor customExecutor) {
        this.context = context.getApplicationContext();
        if (customExecutor != null) {
            this.adbExecutor = customExecutor;
        } else {
            this.adbExecutor = new MultiTierAdbExecutor(this.context);
        }
    }

    /**
     * com.byd.androidauto paketinin sistemde devre dışı olup olmadığını kontrol eder.
     * Hem getApplicationEnabledSetting hem de ApplicationInfo.enabled bayrağını doğrular.
     */
    public boolean isStockAADisabled() {
        try {
            PackageManager pm = context.getPackageManager();
            int state = pm.getApplicationEnabledSetting(TARGET_PACKAGE);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return true;
            }
            ApplicationInfo ai = pm.getApplicationInfo(TARGET_PACKAGE, 0);
            return !ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            // Paket tamamen gizlendiyse (disabled-user) veya yoksa çakışma ortadan kalkmıştır
            return true;
        } catch (Exception e) {
            Log.w(TAG, "isStockAADisabled kontrolünde istisna: " + e.getMessage());
            return false;
        }
    }

    /**
     * Butonun metnini, ikonunu ve alt açıklamasını mevcut duruma göre günceller.
     */
    public void updateButtonUI(View btnContainer, ImageView iconView, TextView titleView, TextView hintView) {
        if (btnContainer == null || titleView == null) return;

        boolean disabled = isStockAADisabled();
        btnContainer.setEnabled(!isBusy.get());
        btnContainer.setAlpha(isBusy.get() ? 0.6f : 1.0f);

        if (isBusy.get()) {
            titleView.setText("⏳ İşlem yapılıyor...");
            if (hintView != null) {
                hintView.setText("ADB üzerinden sistem ayarı uygulanıyor, lütfen bekleyin.");
            }
            return;
        }

        if (disabled) {
            // İdeal durum: Yerleşik AA kapalı, DiAuto aktif
            titleView.setText("Yerleşik AA: Devre Dışı (DiAuto Aktif)");
            if (iconView != null) {
                iconView.setImageResource(android.R.drawable.presence_online);
                iconView.setColorFilter(0xFF4CAF50); // Yeşil onay rengi
            }
            if (hintView != null) {
                hintView.setText("Orijinal Android Auto kapalı. Geri açmak için butona dokunun.");
            }
        } else {
            // Çakışma durumu: Yerleşik AA açık
            titleView.setText("🚫 Yerleşik AA'yı Devre Dışı Bırak");
            if (iconView != null) {
                iconView.setImageResource(android.R.drawable.ic_delete);
                iconView.setColorFilter(0xFFFF5252); // Kırmızı uyarı rengi
            }
            if (hintView != null) {
                hintView.setText("DiAuto'nun otomatik bağlanması için aracın yerleşik AA servisini kapatın.");
            }
        }
    }

    /**
     * Butona basıldığında durumu tersine çevirir (Açık -> Devre Dışı / Devre Dışı -> Açık).
     */
    public void toggleStockAA(View btnContainer, ImageView iconView, TextView titleView, TextView hintView) {
        if (isBusy.compareAndSet(false, true)) {
            updateButtonUI(btnContainer, iconView, titleView, hintView);

            backgroundExecutor.execute(() -> {
                boolean currentlyDisabled = isStockAADisabled();
                boolean targetDisableState = !currentlyDisabled;

                try {
                    String command;
                    if (targetDisableState) {
                        // Önce çalışan servisi durdur, ardından kullanıcı 0 için kalıcı devre dışı bırak
                        command = "am force-stop " + TARGET_PACKAGE + " && pm disable-user --user 0 " + TARGET_PACKAGE;
                    } else {
                        // Paketi tekrar etkinleştir
                        command = "pm enable " + TARGET_PACKAGE;
                    }

                    Log.i(TAG, "ADB Komutu yürütülüyor: " + command);
                    boolean success = adbExecutor.executeShellCommand(command);
                    Log.i(TAG, "ADB Komut sonucu: " + success);

                    // Kullanıcı tercihini kaydet
                    saveUserPreference(targetDisableState);

                    // PackageManager durumunun /data/system/users/0/package-restrictions.xml
                    // dosyasına yazılması için kısa bir bekleme
                    Thread.sleep(400);

                    mainHandler.post(() -> {
                        isBusy.set(false);
                        updateButtonUI(btnContainer, iconView, titleView, hintView);

                        boolean nowDisabled = isStockAADisabled();
                        String toastMsg = nowDisabled
                                ? "✅ Aracın yerleşik Android Auto'su devre dışı bırakıldı."
                                : "ℹ️ Aracın yerleşik Android Auto'su tekrar etkinleştirildi.";
                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "toggleStockAA hatası", e);
                    mainHandler.post(() -> {
                        isBusy.set(false);
                        updateButtonUI(btnContainer, iconView, titleView, hintView);
                        Toast.makeText(context, "ADB Hatası: Kablosuz hata ayıklama yetkisini kontrol edin: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        }
    }

    /**
     * OTA güncellemesi veya araç yeniden açılışı sonrası com.byd.androidauto kendiliğinden açılırsa,
     * uygulama açılışında otomatik olarak tekrar devre dışı bırakır (Auto-Guard).
     */
    public void enforceAutoDisableIfNeeded(Runnable onStateChanged) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean shouldKeepDisabled = prefs.getBoolean(KEY_AUTO_DISABLE, true);

        if (shouldKeepDisabled && !isStockAADisabled() && !isBusy.get()) {
            backgroundExecutor.execute(() -> {
                try {
                    Log.i(TAG, "Auto-Guard devrede: com.byd.androidauto devre dışı bırakılıyor...");
                    adbExecutor.executeShellCommand(
                            "am force-stop " + TARGET_PACKAGE + " && pm disable-user --user 0 " + TARGET_PACKAGE
                    );
                    Thread.sleep(400);
                    if (onStateChanged != null) {
                        mainHandler.post(onStateChanged);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Auto-Guard başarısız: " + e.getMessage());
                }
            });
        }
    }

    private void saveUserPreference(boolean keepDisabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_AUTO_DISABLE, keepDisabled).apply();
    }

    // =========================================================================
    // ÇOK KATMANLI ADB YÜRÜTÜCÜ MİMARİSİ (Kinex Launcher ADB Protokolü + Shizuku + Root)
    // =========================================================================

    public static class MultiTierAdbExecutor implements AdbCommandExecutor {
        private final Context context;

        public MultiTierAdbExecutor(Context context) {
            this.context = context;
        }

        @Override
        public boolean executeShellCommand(String command) throws Exception {
            // Katman 1: Yerel ADB (127.0.0.1:5555 TCP ADB İstemcisi - Kinex Yöntemi)
            try {
                Log.d(TAG, "[Katman 1] Yerel ADB (localhost:5555) deneniyor...");
                String output = executeViaLocalAdb(command);
                Log.d(TAG, "[Katman 1] Yerel ADB başarılı, çıktı: " + output);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "[Katman 1] Yerel ADB başarısız (" + e.getMessage() + "), diğer katmanlar deneniyor...");
            }

            // Katman 2: Shizuku / Root (Projedeki SUExecutor mekanizması)
            try {
                Log.d(TAG, "[Katman 2] Shizuku / Root deneniyor...");
                int exitCode = executeViaSuOrShizuku(command);
                if (exitCode == 0) {
                    Log.d(TAG, "[Katman 2] Shizuku / Root başarılı!");
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "[Katman 2] Shizuku / Root hatası: " + t.getMessage());
            }

            // Katman 3: Yerel Runtime.exec fallback (sh -c)
            try {
                Log.d(TAG, "[Katman 3] Doğrudan shell (sh -c) deneniyor...");
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    Log.d(TAG, "[Katman 3] Doğrudan shell başarılı!");
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "[Katman 3] Shell hatası: " + e.getMessage());
            }

            throw new IOException("Tüm yürütme katmanları başarısız oldu. Lütfen aracın ADB (5555) veya Shizuku yetkisini kontrol edin.");
        }

        private String executeViaLocalAdb(String command) throws Exception {
            File keysDir = new File(context.getFilesDir(), "byd_adb_keys");
            if (!keysDir.exists()) keysDir.mkdirs();

            try (LocalAdbClient adb = LocalAdbClient.connect("127.0.0.1", 5555, keysDir)) {
                return adb.execute(command);
            }
        }

        private int executeViaSuOrShizuku(String command) {
            try {
                // com.andrerinas.openheadunit.utils.SUExecutor yansıma ile çağrılır
                Class<?> suClass = Class.forName("com.andrerinas.openheadunit.utils.SUExecutor");
                Object suInstance = suClass.getDeclaredConstructor().newInstance();
                java.lang.reflect.Method checkMethod = suClass.getMethod("checkPermission");
                Object granted = checkMethod.invoke(suInstance);
                if (Boolean.TRUE.equals(granted)) {
                    java.lang.reflect.Method runMethod = suClass.getMethod("runShell", String.class);
                    Object code = runMethod.invoke(suInstance, command);
                    return code instanceof Integer ? (Integer) code : -1;
                }
            } catch (Throwable ignored) {
            }
            return -1;
        }
    }

    // =========================================================================
    // SAF JAVA YEREL ADB İSTEMCİSİ (AOSP ADB Protokolü - 127.0.0.1:5555)
    // =========================================================================

    public static final class LocalAdbClient implements Closeable {
        private static final int A_CNXN = 0x4e584e43; // "CNXN"
        private static final int A_AUTH = 0x48545541; // "AUTH"
        private static final int A_OPEN = 0x4e45504f; // "OPEN"
        private static final int A_OKAY = 0x59414b4f; // "OKAY"
        private static final int A_CLSE = 0x45534c43; // "CLSE"
        private static final int A_WRTE = 0x45545257; // "WRTE"

        private static final int VERSION = 0x01000000;
        private static final int MAX_PACKET = 4096;
        private static final int AUTH_TOKEN = 1;
        private static final int AUTH_SIGNATURE = 2;
        private static final int AUTH_RSAPUBLICKEY = 3;

        private static final byte[] SHA1_DIGEST_INFO = {
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };

        private final Socket socket;
        private final DataInputStream input;
        private final OutputStream output;
        private volatile boolean closed = false;
        private int localStreamId = 1;

        public static LocalAdbClient connect(String host, int port, File keyStorageDir) throws Exception {
            KeyPair keyPair = loadOrCreateRsaKeyPair(keyStorageDir);
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), 3000);
            sock.setSoTimeout(6000);
            sock.setTcpNoDelay(true);

            LocalAdbClient client = new LocalAdbClient(sock);
            try {
                client.handshake(keyPair);
                return client;
            } catch (Exception e) {
                client.close();
                throw e;
            }
        }

        private LocalAdbClient(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new DataInputStream(socket.getInputStream());
            this.output = socket.getOutputStream();
        }

        private void handshake(KeyPair keyPair) throws Exception {
            byte[] banner = "host::\0".getBytes(StandardCharsets.UTF_8);
            sendPacket(A_CNXN, VERSION, MAX_PACKET, banner);

            boolean signed = false;
            boolean offeredPublicKey = false;

            for (int i = 0; i < 5; i++) {
                Packet p = readPacket();
                if (p.command == A_CNXN) {
                    // Bağlantı başarılı!
                    return;
                }
                if (p.command == A_AUTH && p.arg0 == AUTH_TOKEN && p.payload.length == 20) {
                    if (!signed) {
                        byte[] sig = signChallenge(keyPair.getPrivate(), p.payload);
                        sendPacket(A_AUTH, AUTH_SIGNATURE, 0, sig);
                        signed = true;
                    } else if (!offeredPublicKey) {
                        byte[] pubKey = formatAdbPublicKey((RSAPublicKey) keyPair.getPublic());
                        sendPacket(A_AUTH, AUTH_RSAPUBLICKEY, 0, pubKey);
                        offeredPublicKey = true;
                    } else {
                        throw new IOException("ADB Yetkilendirmesi Bekleniyor: Lütfen ekranda çıkan 'Hata Ayıklamaya İzin Ver' onayını verin.");
                    }
                } else {
                    throw new IOException("Beklenmeyen ADB yanıtı: 0x" + Integer.toHexString(p.command));
                }
            }
            throw new IOException("ADB el sıkışması tamamlanamadı.");
        }

        public synchronized String execute(String shellCommand) throws Exception {
            if (closed) throw new IOException("ADB İstemcisi kapalı");

            int localId = localStreamId++;
            byte[] cmdBytes = ("shell:" + shellCommand + "\0").getBytes(StandardCharsets.UTF_8);
            sendPacket(A_OPEN, localId, 0, cmdBytes);

            int remoteId = 0;
            boolean opened = false;
            ByteArrayOutputStream reply = new ByteArrayOutputStream();

            for (int i = 0; i < 512; i++) {
                Packet p = readPacket();
                if (p.arg1 != localId && p.command != A_CLSE) {
                    continue;
                }

                if (p.command == A_OKAY) {
                    opened = true;
                    remoteId = p.arg0;
                } else if (p.command == A_WRTE) {
                    if (!opened) throw new IOException("Akış onaylanmadan WRTE alındı");
                    reply.write(p.payload);
                    sendPacket(A_OKAY, localId, remoteId, new byte[0]);
                } else if (p.command == A_CLSE) {
                    sendPacket(A_CLSE, localId, p.arg0, new byte[0]);
                    return new String(reply.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            throw new IOException("ADB paket limiti aşıldı");
        }

        private void sendPacket(int cmd, int arg0, int arg1, byte[] payload) throws IOException {
            int checksum = 0;
            for (byte b : payload) checksum += (b & 0xFF);

            ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(cmd);
            header.putInt(arg0);
            header.putInt(arg1);
            header.putInt(payload.length);
            header.putInt(checksum);
            header.putInt(cmd ^ 0xFFFFFFFF);

            output.write(header.array());
            if (payload.length > 0) {
                output.write(payload);
            }
            output.flush();
        }

        private Packet readPacket() throws IOException {
            byte[] header = new byte[24];
            input.readFully(header);
            ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int cmd = buf.getInt();
            int arg0 = buf.getInt();
            int arg1 = buf.getInt();
            int len = buf.getInt();
            int checksum = buf.getInt();
            int magic = buf.getInt();

            if (magic != (cmd ^ 0xFFFFFFFF) || len < 0 || len > MAX_PACKET) {
                throw new IOException("Geçersiz ADB paket başlığı");
            }

            byte[] payload = new byte[len];
            if (len > 0) {
                input.readFully(payload);
            }

            int actual = 0;
            for (byte b : payload) actual += (b & 0xFF);
            if (actual != checksum) {
                throw new IOException("ADB paket checksum uyuşmazlığı");
            }

            return new Packet(cmd, arg0, arg1, payload);
        }

        @Override
        public void close() {
            closed = true;
            try { socket.close(); } catch (Exception ignored) {}
        }

        private static class Packet {
            final int command, arg0, arg1;
            final byte[] payload;
            Packet(int c, int a0, int a1, byte[] p) {
                this.command = c; this.arg0 = a0; this.arg1 = a1; this.payload = p;
            }
        }

        // --- RSA Anahtar ve İmzalama Yardımcıları ---

        private static KeyPair loadOrCreateRsaKeyPair(File dir) throws Exception {
            File keyFile = new File(dir, "diauto_adb.pk8");
            KeyFactory kf = KeyFactory.getInstance("RSA");

            if (keyFile.exists() && keyFile.length() > 256) {
                byte[] enc = new byte[(int) keyFile.length()];
                try (FileInputStream fis = new FileInputStream(keyFile)) {
                    fis.read(enc);
                }
                PrivateKey privKey = kf.generatePrivate(new PKCS8EncodedKeySpec(enc));
                RSAPublicKeySpec pubSpec = null;
                if (privKey instanceof RSAPrivateCrtKey) {
                    RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privKey;
                    pubSpec = new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
                } else {
                    pubSpec = new RSAPublicKeySpec(((RSAPrivateKey) privKey).getModulus(), BigInteger.valueOf(65537));
                }
                RSAPublicKey pubKey = (RSAPublicKey) kf.generatePublic(pubSpec);
                return new KeyPair(pubKey, privKey);
            }

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            try (FileOutputStream fos = new FileOutputStream(keyFile)) {
                fos.write(kp.getPrivate().getEncoded());
                fos.flush();
            }
            return kp;
        }

        private static byte[] signChallenge(PrivateKey key, byte[] token) throws Exception {
            Signature sig = Signature.getInstance("NONEwithRSA");
            sig.initSign(key);
            sig.update(SHA1_DIGEST_INFO);
            sig.update(token);
            return sig.sign();
        }

        private static byte[] formatAdbPublicKey(RSAPublicKey key) {
            BigInteger n = key.getModulus();
            BigInteger r32 = BigInteger.ONE.shiftLeft(32);
            BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
            ByteBuffer buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(64); // RSANUMWORDS
            buf.putInt(n.mod(r32).modInverse(r32).negate().intValue());
            for (int i = 0; i < 64; i++) {
                buf.putInt(n.intValue());
                n = n.shiftRight(32);
            }
            for (int i = 0; i < 64; i++) {
                buf.putInt(rr.intValue());
                rr = rr.shiftRight(32);
            }
            buf.putInt(65537); // e
            String b64 = Base64.getEncoder().encodeToString(buf.array());
            return (b64 + " DiAuto@localhost\0").getBytes(StandardCharsets.US_ASCII);
        }
    }
}
