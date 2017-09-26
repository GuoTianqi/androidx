/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.arch.background.workmanager;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.arch.lifecycle.ProcessLifecycleOwner;
import android.content.Context;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.support.annotation.VisibleForTesting;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * WorkManager is a class used to enqueue persisted work that is guaranteed to run after its
 * constraints are met.
 */
public final class WorkManager implements LifecycleObserver {

    private static final String TAG = "WorkManager";

    private Context mContext;
    private ScheduledExecutorService mForegroundExecutor;
    private ExecutorService mBackgroundExecutor;
    private WorkDatabase mWorkDatabase;
    private ExecutorService mEnqueueExecutor = Executors.newSingleThreadExecutor();
    private WorkExecutionManager mForegroundWorkExecutionMgr;
    private WorkSpecConverter<JobInfo> mWorkSpecConverter;

    private WorkManager(Context context, Builder builder) {
        mContext = context.getApplicationContext();
        mForegroundExecutor =
                (builder.mForegroundExecutor == null)
                        ? Executors.newScheduledThreadPool(4)   // TODO: Configure intelligently.
                        : builder.mForegroundExecutor;
        mBackgroundExecutor =
                (builder.mBackgroundExecutor == null)
                        ? Executors.newSingleThreadExecutor()   // TODO: Configure intelligently.
                        : builder.mBackgroundExecutor;
        mWorkDatabase = WorkDatabase.create(mContext, builder.mUseInMemoryDatabase);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);

        // TODO(janclarin): Wrap JobScheduler logic behind another interface.
        if (Build.VERSION.SDK_INT >= 21) {
            mWorkSpecConverter = new JobSchedulerConverter(mContext);
        }
    }

    @VisibleForTesting
    WorkDatabase getWorkDatabase() {
        return mWorkDatabase;
    }

    /**
     * Called when the process lifecycle is considered started.
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onLifecycleStart() {
        mForegroundWorkExecutionMgr = new WorkExecutionManager(
                mContext,
                mWorkDatabase,
                mForegroundExecutor,
                null /* TODO: scheduler */);
    }

    /**
     * Called when the process lifecycle is considered stopped.
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onLifecycleStop() {
        mForegroundWorkExecutionMgr.shutdown();
        mForegroundWorkExecutionMgr = null;
    }

    /**
     * Enqueues an item for background processing.
     *
     * @param work The {@link Work} to enqueue
     * @return A {@link WorkContinuation} that allows further chaining
     */
    public WorkContinuation enqueue(Work work) {
        return enqueue(work, null);
    }

    /**
     * Enqueues an item for background processing.
     *
     * @param workBuilder The {@link Work.Builder} to enqueue; internally {@code build} is called
     *                    on it
     * @return A {@link WorkContinuation} that allows further chaining
     */
    public WorkContinuation enqueue(Work.Builder workBuilder) {
        return enqueue(workBuilder.build(), null);
    }

    /**
     * Enqueues an item for background processing.
     *
     * @param workerClass The {@link Worker} to enqueue; this is a convenience method that makes a
     *                    {@link Work} object with default arguments using this Worker
     * @return A {@link WorkContinuation} that allows further chaining
     */
    public WorkContinuation enqueue(Class<? extends Worker> workerClass) {
        return enqueue(new Work.Builder(workerClass).build(), null);
    }

    WorkContinuation enqueue(Work work, String prerequisiteId) {
        WorkContinuation workContinuation = new WorkContinuation(this, work.getId());
        mEnqueueExecutor.execute(new EnqueueRunnable(work, prerequisiteId));
        return workContinuation;
    }

    /**
     * A Runnable to enqueue a {@link Work} in the database.
     */
    private class EnqueueRunnable implements Runnable {
        private Work mWork;
        private String mPrerequisiteId;

        EnqueueRunnable(Work work, String prerequisiteId) {
            mWork = work;
            mPrerequisiteId = prerequisiteId;
        }

        @Override
        public void run() {
            mWorkDatabase.beginTransaction();
            try {
                mWorkDatabase.workSpecDao().insertWorkSpec(mWork.getWorkSpec());
                if (mPrerequisiteId != null) {
                    Dependency dep = new Dependency(mWork.getId(), mPrerequisiteId);
                    mWorkDatabase.dependencyDao().insertDependency(dep);
                } else {
                    if (mForegroundWorkExecutionMgr != null) {
                        mForegroundWorkExecutionMgr.enqueue(
                                mWork.getId(),
                                0L /* TODO: delay */);
                        // TODO: Schedule dependent work.
                    }

                    if (Build.VERSION.SDK_INT >= 21) {
                        scheduleWorkWithJobScheduler();
                        // TODO(janclarin): Schedule dependent work.
                    }
                }

                mWorkDatabase.setTransactionSuccessful();
            } finally {
                mWorkDatabase.endTransaction();
            }
        }

        @RequiresApi(api = 21)
        private void scheduleWorkWithJobScheduler() {
            JobScheduler jobScheduler =
                    (JobScheduler) mContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobScheduler != null) {
                JobInfo jobInfo = mWorkSpecConverter.convert(mWork.getWorkSpec());
                jobScheduler.schedule(jobInfo);
            }
        }
    }

    /**
     * A Builder for {@link WorkManager}.
     */
    public static class Builder {

        private ScheduledExecutorService mForegroundExecutor;
        private ExecutorService mBackgroundExecutor;
        private boolean mUseInMemoryDatabase;

        /**
         * @param foregroundExecutor The ExecutorService to run in-process during active lifecycles
         * @return The Builder
         */
        public Builder withForegroundExecutor(ScheduledExecutorService foregroundExecutor) {
            mForegroundExecutor = foregroundExecutor;
            return this;
        }

        /**
         * @param backgroundExecutor The ExecutorService to run via OS-defined background execution
         *                           such as {@link android.app.job.JobScheduler}
         * @return The Builder
         */
        public Builder withBackgroundExecutor(ExecutorService backgroundExecutor) {
            mBackgroundExecutor = backgroundExecutor;
            return this;
        }

        /**
         * Call this method to use an in-memory database.  Useful for tests.
         *
         * @return The Builder
         */
        @VisibleForTesting
        Builder withInMemoryDatabase() {
            mUseInMemoryDatabase = true;
            return this;
        }

        /**
         * Builds the {@link WorkManager}.
         *
         * @param context The context used for initialization (we will get the Application context)
         * @return The {@link WorkManager}
         */
        public WorkManager build(Context context) {
            return new WorkManager(context, this);
        }
    }
}

