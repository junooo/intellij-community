/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vcs.changes;

import com.intellij.ide.startup.impl.StartupManagerImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.SomeQueue;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.io.storage.HeavyProcessLatch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.intellij.util.ObjectUtils.notNull;

/**
 * ChangeListManager updates scheduler.
 * Tries to zip several update requests into one (if starts and see several requests in the queue)
 * own inner synchronization
 */
@SomeQueue
public class UpdateRequestsQueue {
  private final Logger LOG = Logger.getInstance("#com.intellij.openapi.vcs.changes.UpdateRequestsQueue");
  private final boolean myTrackHeavyLatch = Boolean.parseBoolean(System.getProperty("vcs.local.changes.track.heavy.latch"));

  private final Project myProject;
  private final ChangeListManagerImpl.Scheduler myScheduler;
  private final Runnable myDelegate;
  private final Object myLock = new Object();
  private volatile boolean myStarted;
  private volatile boolean myStopped;
  private volatile boolean myIgnoreBackgroundOperation;

  private boolean myRequestSubmitted;
  private boolean myRequestRunning;
  private final List<Runnable> myWaitingUpdateCompletionQueue = new ArrayList<>();
  private final List<Semaphore> myWaitingUpdateCompletionSemaphores = new ArrayList<>();
  private final ProjectLevelVcsManager myPlVcsManager;
  //private final ScheduledSlowlyClosingAlarm mySharedExecutor;
  private final StartupManager myStartupManager;

  public UpdateRequestsQueue(final Project project, @NotNull ChangeListManagerImpl.Scheduler scheduler, final Runnable delegate) {
    myProject = project;
    myScheduler = scheduler;

    myDelegate = delegate;
    myPlVcsManager = ProjectLevelVcsManager.getInstance(myProject);
    myStartupManager = StartupManager.getInstance(myProject);

    // not initialized
    myStarted = false;
    myStopped = false;
  }

  public void initialized() {
    LOG.debug("Initialized for project: " + myProject.getName());
    myStarted = true;
  }

  public boolean isStopped() {
    return myStopped;
  }

  public void schedule() {
    synchronized (myLock) {
      if (!myStarted && ApplicationManager.getApplication().isUnitTestMode()) return;

      if (myStopped) return;
      if (myRequestSubmitted) return;
      myRequestSubmitted = true;

      final MyRunnable runnable = new MyRunnable();
      myScheduler.schedule(runnable, 300, TimeUnit.MILLISECONDS);
      LOG.debug("Scheduled for project: " + myProject.getName() + ", runnable: " + runnable.hashCode());
    }
  }

  public void pause() {
    synchronized (myLock) {
      myStopped = true;
    }
  }

  @TestOnly
  public void forceGo() {
    synchronized (myLock) {
      myStopped = false;
      myRequestSubmitted = false;
      myRequestRunning = false;
    }
    schedule();
  }

  public void go() {
    synchronized (myLock) {
      myStopped = false;
    }
    schedule();
  }

  public void stop() {
    LOG.debug("Calling stop for project: " + myProject.getName());
    final List<Runnable> waiters = new ArrayList<>(myWaitingUpdateCompletionQueue.size());
    synchronized (myLock) {
      myStopped = true;
      waiters.addAll(myWaitingUpdateCompletionQueue);
      myWaitingUpdateCompletionQueue.clear();
    }
    LOG.debug("Calling runnables in stop for project: " + myProject.getName());
    // do not run under lock
    for (Runnable runnable : waiters) {
      runnable.run();
    }
    LOG.debug("Stop finished for project: " + myProject.getName());
  }

  @TestOnly
  public void waitUntilRefreshed() {
    while (true) {
      final Semaphore semaphore = new Semaphore();
      synchronized (myLock) {
        if (!myRequestSubmitted && !myRequestRunning) {
          return;
        }

        if (!myRequestRunning) {
          myScheduler.submit(new MyRunnable());
        }

        semaphore.down();
        myWaitingUpdateCompletionSemaphores.add(semaphore);
      }
      if (!semaphore.waitFor(100 * 1000)) {
        LOG.error("Too long VCS update");
        return;
      }
    }
  }

  private void freeSemaphores() {
    synchronized (myLock) {
      for (Semaphore semaphore : myWaitingUpdateCompletionSemaphores) {
        semaphore.up();
      }
      myWaitingUpdateCompletionSemaphores.clear();
    }
  }

  public void invokeAfterUpdate(@NotNull Runnable afterUpdate,
                                @NotNull InvokeAfterUpdateMode mode,
                                @Nullable String title,
                                @Nullable ModalityState state) {
    LOG.debug("invokeAfterUpdate for project: " + myProject.getName());
    final CallbackData data = CallbackData.create(myProject, mode, afterUpdate, title, state);

    boolean stopped;
    synchronized (myLock) {
      stopped = myStopped;
      if (!stopped) {
        myWaitingUpdateCompletionQueue.add(data.getCallback());
        schedule();
      }
    }
    if (stopped) {
      LOG.debug("invokeAfterUpdate: stopped, invoke right now for project: " + myProject.getName());
      ApplicationManager.getApplication().invokeLater(() -> {
        if (!myProject.isDisposed()) {
          afterUpdate.run();
        }
      }, notNull(state, ModalityState.defaultModalityState()));
      return;
    }
    // invoke progress if needed
    data.getWrapperStarter().run();
    LOG.debug("invokeAfterUpdate: exit for project: " + myProject.getName());
  }

  // true = do not execute
  private boolean checkHeavyOperations() {
    if (myIgnoreBackgroundOperation) return false;
    return myPlVcsManager.isBackgroundVcsOperationRunning() || myTrackHeavyLatch && HeavyProcessLatch.INSTANCE.isRunning();
  }

  // true = do not execute
  private boolean checkLifeCycle() {
    return !myStarted || !((StartupManagerImpl)myStartupManager).startupActivityPassed();
  }

  private class MyRunnable implements Runnable {
    public void run() {
      final List<Runnable> copy = new ArrayList<>(myWaitingUpdateCompletionQueue.size());
      try {
        synchronized (myLock) {
          if (!myRequestSubmitted) return;
          myRequestSubmitted = false;

          LOG.assertTrue(!myRequestRunning);
          myRequestRunning = true;
          if (myStopped) {
            LOG.debug("MyRunnable: STOPPED, project: " + myProject.getName() + ", runnable: " + hashCode());
            return;
          }

          if (checkLifeCycle() || checkHeavyOperations()) {
            LOG.debug("MyRunnable: reschedule, project: " + myProject.getName() + ", runnable: " + hashCode());
            // try again after time
            schedule();
            return;
          }

          copy.addAll(myWaitingUpdateCompletionQueue);
          myWaitingUpdateCompletionQueue.clear();
        }

        LOG.debug("MyRunnable: INVOKE, project: " + myProject.getName() + ", runnable: " + hashCode());
        myDelegate.run();
        LOG.debug("MyRunnable: invokeD, project: " + myProject.getName() + ", runnable: " + hashCode());
      }
      finally {
        synchronized (myLock) {
          myRequestRunning = false;
          LOG.debug("MyRunnable: delete executed, project: " + myProject.getName() + ", runnable: " + hashCode());

          if (!myWaitingUpdateCompletionQueue.isEmpty() && !myRequestSubmitted && !myStopped) {
            LOG.error("No update task to handle request(s)");
          }
        }
        // do not run under lock
        for (Runnable runnable : copy) {
          runnable.run();
        }
        freeSemaphores();
        LOG.debug("MyRunnable: Runnables executed, project: " + myProject.getName() + ", runnable: " + hashCode());
      }
    }

    @Override
    public String toString() {
      return "UpdateRequestQueue delegate: " + myDelegate;
    }
  }

  public void setIgnoreBackgroundOperation(boolean ignoreBackgroundOperation) {
    myIgnoreBackgroundOperation = ignoreBackgroundOperation;
  }
}
