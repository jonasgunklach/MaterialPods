package app.materialpods;

// Runs as the shell user via Shizuku (see PrivilegedService).
interface IPrivileged {
    void setBattery(String mac, int left, int right, int caseLevel, boolean leftCharging, boolean rightCharging, boolean caseCharging) = 1;
    boolean connectAudio(String mac) = 2;
    boolean reconnectA2dp(String mac) = 3;
    void destroy() = 16777114; // Shizuku's reserved "destroy" transaction
}
