// COMPILE-CHECK STUB ONLY — never shipped.
package androidx.biometric;

public class BiometricManager {
    public static final int BIOMETRIC_SUCCESS = 0;
    public static final int BIOMETRIC_ERROR_NONE_ENROLLED = 11;
    public interface Authenticators {
        int BIOMETRIC_STRONG = 0x000F;
        int BIOMETRIC_WEAK = 0x00FF;
        int DEVICE_CREDENTIAL = 1 << 15;
    }
    public static BiometricManager from(android.content.Context context) { return null; }
    public int canAuthenticate(int authenticators) { return 0; }
}
