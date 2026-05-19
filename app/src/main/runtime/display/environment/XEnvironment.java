package com.winlator.cmod.runtime.display.environment;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

public class XEnvironment implements Iterable<EnvironmentComponent> {
  private static final String TAG = "XEnvironment";
  private final Context context;
  private SharedPreferences preferences;
  private final ImageFs imageFs;
  private final ArrayList<EnvironmentComponent> components = new ArrayList<>();

  public XEnvironment(Context context, ImageFs imageFs) {
    this.context = context;
    this.imageFs = imageFs;
  }

  public Context getContext() {
    return context;
  }

  public ImageFs getImageFs() {
    return imageFs;
  }

  public void addComponent(EnvironmentComponent environmentComponent) {
    environmentComponent.environment = this;
    components.add(environmentComponent);
  }

  public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
    for (EnvironmentComponent component : components) {
      if (component.getClass() == componentClass) return (T) component;
    }
    return null;
  }

  @Override
  public Iterator<EnvironmentComponent> iterator() {
    return components.iterator();
  }

  public File getTmpDir() {
    File tmpDir = new File(context.getFilesDir(), "tmp");
    if (!tmpDir.isDirectory()) {
      tmpDir.mkdirs();
      FileUtils.chmod(tmpDir, 0771);
    }
    return tmpDir;
  }

  public void startEnvironmentComponents() {
    FileUtils.clear(getTmpDir());
    Log.d(TAG, "Starting " + components.size() + " environment component(s)");
    for (EnvironmentComponent environmentComponent : this) {
      Log.d(TAG, "Starting component " + environmentComponent.getClass().getSimpleName());
      environmentComponent.start();
    }
    Log.d(TAG, "Environment component startup finished");
  }

  public void stopEnvironmentComponents() {
    // Stop in reverse order so dependent components (guest launcher) tear down before
    // their underlying services (audio sockets, XServer, shm).
    Log.d(TAG, "Stopping " + components.size() + " environment component(s)");
    RuntimeException firstFailure = null;
    for (int i = components.size() - 1; i >= 0; i--) {
      EnvironmentComponent component = components.get(i);
      String name = component.getClass().getSimpleName();
      try {
        Log.d(TAG, "Stopping component " + name);
        component.stop();
        Log.d(TAG, "Stopped component " + name);
      } catch (RuntimeException e) {
        Log.e(TAG, "Component stop failed for " + name, e);
        if (firstFailure == null) firstFailure = e;
      }
    }
    if (firstFailure != null) {
      Log.e(TAG, "Environment component shutdown finished with failure(s)", firstFailure);
    }
    Log.d(TAG, "Environment component shutdown finished");
  }

  public void onPause() {
    if (!shouldPauseOnBackground()) return;
    GuestProgramLauncherComponent guestProgramLauncherComponent =
        getComponent(GuestProgramLauncherComponent.class);
    if (guestProgramLauncherComponent != null) guestProgramLauncherComponent.suspendProcess();
  }

  public void onResume() {
    if (!shouldPauseOnBackground()) return;
    GuestProgramLauncherComponent guestProgramLauncherComponent =
        getComponent(GuestProgramLauncherComponent.class);
    if (guestProgramLauncherComponent != null) guestProgramLauncherComponent.resumeProcess();
  }

  private boolean shouldPauseOnBackground() {
    if (preferences == null) {
      // Set preferences if context doesn't return null
      preferences = context != null ? PreferenceManager.getDefaultSharedPreferences(context) : null;
    }
    // Default to false if preferences is null, otherwise return the value from preferences
    return preferences != null && preferences.getBoolean("pause_on_background", false);
  }

}
