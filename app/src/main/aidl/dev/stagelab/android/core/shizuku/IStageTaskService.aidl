package dev.stagelab.android.core.shizuku;

interface IStageTaskService {
    String getRuntimeIdentity() = 1;
    String probeTaskOrganizer() = 2;
    String getCallbackEvents() = 3;
    String unregisterTaskOrganizer() = 4;
    String request(String commandJson) = 5;
    void destroy() = 16777114;
}
