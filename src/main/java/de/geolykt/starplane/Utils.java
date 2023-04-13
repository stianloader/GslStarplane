package de.geolykt.starplane;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Collection of static utility methods originating from the starloader-launcher.
 */
public final class Utils {

    public static final String OPERATING_SYSTEM = System.getProperty("os.name");

    @Nullable
    private static final MethodHandle READ_ALL_BYTES_HANDLE;
    public static final int STEAM_GALIMULATOR_APPID = 808100;

    public static final String STEAM_GALIMULATOR_APPNAME = "Galimulator";

    public static final String STEAM_WINDOWS_REGISTRY_INSTALL_DIR_KEY = "InstallPath";

    public static final String STEAM_WINDOWS_REGISTRY_KEY = "HKEY_LOCAL_MACHINE\\SOFTWARE\\Wow6432Node\\Valve\\Steam";

    static {
        MethodHandle handle = null;
        try {
            handle = MethodHandles.publicLookup().findVirtual(InputStream.class, "readAllBytes", MethodType.fromMethodDescriptorString("()[B", Utils.class.getClassLoader()));
        } catch (Exception ignore) {}
        READ_ALL_BYTES_HANDLE = handle;
    }

    public static final String getChecksum(File file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        if (!file.exists()) {
            throw new RuntimeException("Jar was not found!");
        }
        try (DigestInputStream digestStream = new DigestInputStream(new FileInputStream(file), digest)) {
            MethodHandle mh = READ_ALL_BYTES_HANDLE;
            if (mh != null) {
                mh.invoke(digestStream); // This should be considerably faster than the other methods, however only got introduced in Java 9
            } else {
                while (digestStream.read() != -1) {
                    // Empty block; Read all bytes
                }
            }
            digest = digestStream.getMessageDigest();
        } catch (Throwable t) {
            throw new RuntimeException("Something went wrong while obtaining the checksum of the galimulator jar.", t);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    @NotNull
    public static final File getCurrentDir() {
        return new File(".");
    }

    @Nullable
    public static final File getGameDir(@NotNull String game) {
        File steamExec = getSteamExecutableDir();
        if (steamExec == null || !steamExec.exists()) {
            if (OPERATING_SYSTEM.toLowerCase(Locale.ROOT).startsWith("win")) {
                steamExec = getOneOfExistingFiles("C:\\Steam\\", "C:\\Program Files (x86)\\Steam\\", "C:\\Program Files\\Steam\\", "D:\\Steam\\", "C:\\Programmes\\Steam\\", "D:\\Programmes\\Steam\\");
            }
            if (steamExec == null) {
                return null;
            }
        }
        if (!steamExec.isDirectory()) {
            throw new IllegalStateException("Steam executable directory not a directory.");
        }
        File appdata = new File(steamExec, "steamapps");
        File common = new File(appdata, "common");
        return new File(common, game);
    }

    @Nullable
    public static final File getOneOfExistingFiles(@NotNull String... paths) {
        for (String path : paths) {
            File file = new File(path);
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }

    public static final Path getSourceJar(Class<?> clazz) {
        URL url = clazz.getProtectionDomain().getCodeSource().getLocation();
        String path = url.getPath();
        if (path.contains("!")) {
            path = path.substring(0, path.indexOf('!'));
        }
        return Path.of(path);
    }

    @Nullable
    public static final File getSteamExecutableDir() {
        if (OPERATING_SYSTEM.toLowerCase(Locale.ROOT).startsWith("win")) {
            String val = readWindowsRegistry(STEAM_WINDOWS_REGISTRY_KEY, STEAM_WINDOWS_REGISTRY_INSTALL_DIR_KEY);
            System.out.println(val);
            if (val == null) {
                return null;
            }
            return new File(val);
        } else {
            // Assuming UNIX, though for real we should check other OSes
            String homeDir = System.getProperty("user.home");
            if (homeDir == null) {
                return null;
            }
            File usrHome = new File(homeDir);
            File steamHome = new File(usrHome, ".steam");
            if (steamHome.exists()) {
                // some installs have the steam directory located in ~/.steam/debian-installation
                File debianInstall = new File(steamHome, "debian-installation");
                if (debianInstall.exists()) {
                    return debianInstall;
                } else {
                    return new File(steamHome, "steam");
                }
            }
            // Steam folder not located in ~/.steam, checking in ~/.local/share
            File local = new File(usrHome, ".local");
            if (!local.exists()) {
                return null; // Well, we tried...
            }
            File share = new File(local, "share");
            if (!share.exists()) {
                return null;
            }
            return new File(share, "Steam");
        }
    }

    public static final boolean isBlank(@NotNull String string) {
        int length = string.length();
        for (int i = 0; i < length; i++) {
            if (!Character.isWhitespace(string.codePointAt(i))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("null")
    public static byte @NotNull[] readAllBytes(InputStream is) throws IOException {
        MethodHandle mh = READ_ALL_BYTES_HANDLE;
        if (mh == null) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int read;
            while ((read = is.read(b)) != -1) {
                bytes.write(b, 0, read);
            }
            return bytes.toByteArray();
        } else {
            try {
                return (byte @NotNull[]) mh.invoke(is);
            } catch (Throwable e) {
                throw new IOException("Unable to read all bytes via the method handle", e);
            }
        }
    }

    /**
     * Stupid little hack.
     *
     * @param location path in the registry
     * @param key registry key
     * @return registry value or null if not found
     * @author Oleg Ryaboy, based on work by Miguel Enriquez; Made blocking by Geolykt
     */
    public static final String readWindowsRegistry(String location, String key) {
        try {
            // Run reg query, then read it's output
            Process process = Runtime.getRuntime().exec("reg query " + '"' + location + "\" /v " + key);

            process.waitFor();
            InputStream is = process.getInputStream();
            String output = new String(readAllBytes(is), StandardCharsets.UTF_8);
            is.close();

            if (!output.contains(location) || !output.contains(key)) {
                return null;
            }

            // Parse out the value
            // For me this results in:
            // [, HKEY_LOCAL_MACHINE\SOFTWARE\Wow6432Node\Valve\Steam, InstallPath, REG_SZ, D:\Programmes\Steam]
            String[] parsed = output.split("\\s+");
            return parsed[parsed.length-1];
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
