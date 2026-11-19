package android.window;

import android.app.ActivityManager;
import android.view.SurfaceControl;
import java.util.List;

/**
 * Compile-only API surface for the hidden platform TaskOrganizer class.
 * The real implementation is supplied by the Android framework at runtime.
 */
public class TaskOrganizer {
    public TaskOrganizer() {}

    public List registerOrganizer() { return null; }

    public void unregisterOrganizer() {}

    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {}

    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {}

    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {}

    public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {}
}
