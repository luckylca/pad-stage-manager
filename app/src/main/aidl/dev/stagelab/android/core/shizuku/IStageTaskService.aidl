package dev.stagelab.android.core.shizuku;

interface IStageTaskService {
    String getRuntimeIdentity() = 1;
    String probeTaskOrganizer() = 2;
    String getCallbackEvents() = 3;
    String unregisterTaskOrganizer() = 4;
    void destroy() = 16777114;
}
