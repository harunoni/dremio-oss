/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.reflection;

import static com.dremio.common.utils.PathUtils.constructFullPath;
import static com.dremio.exec.ExecConstants.LAYOUT_REFRESH_MAX_ATTEMPTS;
import static com.dremio.service.reflection.ReflectionOptions.REFLECTION_DELETION_GRACE_PERIOD;
import static com.dremio.service.reflection.ReflectionOptions.REFLECTION_DELETION_NUM_ENTRIES;
import static com.dremio.service.reflection.ReflectionUtils.getId;
import static com.dremio.service.reflection.ReflectionUtils.getMaterializationPath;
import static com.dremio.service.reflection.proto.ReflectionState.ACTIVE;
import static com.dremio.service.reflection.proto.ReflectionState.DEPRECATE;
import static com.dremio.service.reflection.proto.ReflectionState.FAILED;
import static com.dremio.service.reflection.proto.ReflectionState.METADATA_REFRESH;
import static com.dremio.service.reflection.proto.ReflectionState.REFRESH;
import static com.dremio.service.reflection.proto.ReflectionState.REFRESHING;
import static com.dremio.service.reflection.proto.ReflectionState.UPDATE;
import static com.dremio.service.users.SystemUser.SYSTEM_USERNAME;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.dremio.datastore.WarningTimer;
import com.dremio.exec.server.options.OptionManager;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.work.user.SubstitutionSettings;
import com.dremio.service.job.proto.JobAttempt;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.MaterializationSummary;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.jobs.Job;
import com.dremio.service.jobs.JobException;
import com.dremio.service.jobs.JobNotFoundException;
import com.dremio.service.jobs.JobRequest;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.jobs.NoOpJobStatusListener;
import com.dremio.service.jobs.SqlQuery;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.reflection.ReflectionServiceImpl.DescriptorCache;
import com.dremio.service.reflection.ReflectionServiceImpl.ExpansionHelper;
import com.dremio.service.reflection.handlers.RefreshDoneHandler;
import com.dremio.service.reflection.handlers.RefreshStartHandler;
import com.dremio.service.reflection.proto.ExternalReflection;
import com.dremio.service.reflection.proto.Failure;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.MaterializationState;
import com.dremio.service.reflection.proto.ReflectionEntry;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionGoalState;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.ReflectionState;
import com.dremio.service.reflection.proto.RefreshDecision;
import com.dremio.service.reflection.store.ExternalReflectionStore;
import com.dremio.service.reflection.store.MaterializationStore;
import com.dremio.service.reflection.store.ReflectionEntriesStore;
import com.dremio.service.reflection.store.ReflectionGoalsStore;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

/**
 * Manages reflections, excluding external reflections, by observing changes to the reflection goals, datasets, materialization
 * jobs and executing the appropriate handling logic sequentially.
 */
public class ReflectionManager implements Runnable {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ReflectionManager.class);

  /**
   * Callback that allows async handlers to wake up the manager once they are done.
   */
  public interface WakeUpCallback {
    void wakeup(String reason);
  }

  /**
   * when the manager wakes up, it looks at all reflection goals that have been added/modified since the last wakeup.
   * this assumes that entries saved to the kvStore will instantaneously be available, but in practice there will always
   * be a slight delay.
   * this constant defines protects against skipping those entries.
   */
  private static final long WAKEUP_OVERLAP_MS = 10;

  private final JobsService jobsService;
  private final NamespaceService namespaceService;
  private final OptionManager optionManager;
  private final ReflectionGoalsStore userStore;
  private final ReflectionEntriesStore reflectionStore;
  private final ExternalReflectionStore externalReflectionStore;
  private final MaterializationStore materializationStore;
  private final FileSystemPlugin accelerationPlugin;
  private final DependencyManager dependencyManager;
  private final DescriptorCache descriptorCache;
  private final Set<ReflectionId> reflectionsToUpdate;
  private final WakeUpCallback wakeUpCallback;
  private final Supplier<ExpansionHelper> expansionHelper;

  private long lastWakeupTime;

  ReflectionManager(JobsService jobsService, NamespaceService namespaceService, OptionManager optionManager,
                    ReflectionGoalsStore userStore, ReflectionEntriesStore reflectionStore,
                    ExternalReflectionStore externalReflectionStore, MaterializationStore materializationStore,
                    FileSystemPlugin accelerationPlugin, DependencyManager dependencyManager,
                    DescriptorCache descriptorCache, Set<ReflectionId> reflectionsToUpdate,
                    WakeUpCallback wakeUpCallback, Supplier<ExpansionHelper> expansionHelper) {
    this.jobsService = Preconditions.checkNotNull(jobsService, "jobsService required");
    this.namespaceService = Preconditions.checkNotNull(namespaceService, "namespaceService required");
    this.optionManager = Preconditions.checkNotNull(optionManager, "optionManager required");
    this.userStore = Preconditions.checkNotNull(userStore, "reflection user store required");
    this.reflectionStore = Preconditions.checkNotNull(reflectionStore, "reflection store required");
    this.externalReflectionStore = Preconditions.checkNotNull(externalReflectionStore);
    this.materializationStore = Preconditions.checkNotNull(materializationStore, "materialization store required");
    this.accelerationPlugin = Preconditions.checkNotNull(accelerationPlugin, "acceleration storage plugin required");
    this.dependencyManager = Preconditions.checkNotNull(dependencyManager, "dependency manager required");
    this.descriptorCache = Preconditions.checkNotNull(descriptorCache, "descriptor cache required");
    this.reflectionsToUpdate = Preconditions.checkNotNull(reflectionsToUpdate, "reflections to update required");
    this.wakeUpCallback = Preconditions.checkNotNull(wakeUpCallback, "wakeup callback required");
    this.expansionHelper = Preconditions.checkNotNull(expansionHelper, "sqlConvertSupplier requiered");
  }

  @Override
  public void run() {
    try (WarningTimer timer = new WarningTimer("Reflection Manager", TimeUnit.SECONDS.toMillis(5))) {
      logger.trace("running the reflection manager");
      final long previousLastWakeupTime = lastWakeupTime - WAKEUP_OVERLAP_MS;
      // updating the store's lastWakeupTime here. This ensures that if we're failing we don't do a denial of service attack
      // this assumes we properly handle exceptions for each goal/entry independently and we don't exit the loop before we
      // go through all entities otherwise we may "skip" handling some entities in case of failures
      lastWakeupTime = System.currentTimeMillis();
      final long deletionGracePeriod = optionManager.getOption(REFLECTION_DELETION_GRACE_PERIOD) * 1000;
      final long deletionThreshold = System.currentTimeMillis() - deletionGracePeriod;
      final int numEntriesToDelete = (int) optionManager.getOption(REFLECTION_DELETION_NUM_ENTRIES);

      try {
        handleReflectionsToUpdate();
        handleDeletedDatasets();
        handleGoals(previousLastWakeupTime);
        handleEntries();
        deleteDeprecatedMaterializations(deletionThreshold, numEntriesToDelete);
        deprecateMaterializations();
        deleteDeprecatedGoals(deletionThreshold);
      } catch (Throwable e) {
        logger.error("Reflection manager failed", e);
      }
    }
  }

  /**
   * handle all reflections marked by the reflection service as need to update.<br>
   * those are reflections with plans that couldn't be expended and thus need to be set in UPDATE state
   */
  private void handleReflectionsToUpdate() {
    final Iterator<ReflectionId> iterator = reflectionsToUpdate.iterator();
    while (iterator.hasNext()) {
      final ReflectionId rId = iterator.next();
      try {
        final ReflectionEntry entry = reflectionStore.get(rId);
        if (entry != null) {
          cancelRefreshJobIfAny(entry);
          entry.setState(UPDATE);
          reflectionStore.save(entry);
        }
      } finally {
        // block should never throw, but in case it does we don't want be stuck trying to update the same entry
        iterator.remove();
      }
    }
  }

  /**
   * 4th pass: remove any deleted goal that's due
   *
   * @param deletionThreshold thrshold after which deprecated reflection goals are deleted
   */
  private void deleteDeprecatedGoals(long deletionThreshold) {
    Iterable<ReflectionGoal> goalsDueForDeletion = userStore.getDeletedBefore(deletionThreshold);
    for (ReflectionGoal goal : goalsDueForDeletion) {
      logger.debug("reflection goal {} due for deletion", goal.getId().getId());
      userStore.delete(goal.getId());
    }
  }

  private void deprecateMaterializations() {
    final long now = System.currentTimeMillis();
    Iterable<Materialization> materializations = materializationStore.getAllExpiredWhen(now);
    for (Materialization materialization : materializations) {
      try {
        deprecateMaterialization(materialization);
      } catch (Exception e) {
        logger.warn("Couldn't deprecate materialization {}", getId(materialization));
      }
    }
  }

  /**
   * 3rd pass: go through the materialization store
   *
   * @param deletionThreshold threshold time after which deprecated materialization are deleted
   * @param numEntries number of entries that should be deleted now
   */
  private void deleteDeprecatedMaterializations(long deletionThreshold, int numEntries) {
    Iterable<Materialization> materializations = materializationStore.getDeletableEntriesModifiedBefore(deletionThreshold, numEntries);
    for (Materialization materialization : materializations) {
      logger.debug("deprecated materialization {} due for deletion", getId(materialization));
      try {
        deleteMaterialization(materialization);
      } catch (Exception e) {
        logger.warn("Couldn't delete deprecated materialization {}", getId(materialization));
      }
    }
  }

  /**
   * 2nd pass: go through the reflection store
   */
  private void handleEntries() {
    final long noDependencyRefreshPeriodMs = optionManager.getOption(ReflectionOptions.NO_DEPENDENCY_REFRESH_PERIOD_SECONDS) * 1000;

    Iterable<ReflectionEntry> entries = reflectionStore.find();
    for (ReflectionEntry entry : entries) {
      try {
        handleEntry(entry, noDependencyRefreshPeriodMs);
      } catch (Exception e) {
        logger.error("Couldn't handle reflection entry {}", entry.getId().getId(), e);
        reportFailure(entry, entry.getState());
      }
    }
  }

  private void handleDeletedDatasets() {
    Iterable<ReflectionGoal> goals = userStore.getAllNotDeleted();
    for (ReflectionGoal goal : goals) {
      handleDatasetDeletion(goal.getDatasetId(), goal.getId());
    }

    Iterable<ExternalReflection> externalReflections = externalReflectionStore.getExternalReflections();
    for (ExternalReflection externalReflection : externalReflections) {
      handleDatasetDeletion(externalReflection.getQueryDatasetId(), new ReflectionId(externalReflection.getId()));
    }
  }

  private void handleEntry(ReflectionEntry entry, final long noDependencyRefreshPeriodMs) {
    final ReflectionState state = entry.getState();
    switch (state) {
      case FAILED:
        // do nothing
        //TODO filter out those when querying the reflection store
        break;
      case REFRESHING:
      case METADATA_REFRESH:
        handleRefreshingEntry(entry);
        break;
      case UPDATE:
        deprecateMaterializations(entry);
        startRefresh(entry);
        break;
      case ACTIVE:
        if (!dependencyManager.shouldRefresh(entry.getId(), noDependencyRefreshPeriodMs)) {
          // only refresh ACTIVE reflections when they are due for refresh
          break;
        }
      case REFRESH:
        logger.info("reflection {} is due for refresh", getId(entry));
        startRefresh(entry);
        break;
      case DEPRECATE:
        deprecateMaterializations(entry);
        deleteReflection(entry);
        break;
      default:
        throw new IllegalStateException("Unsupported reflection state " + state);
    }
  }

  /**
   * handles entry in REFRESHING/METADATA_REFRESH state
   */

  private void handleRefreshingEntry(final ReflectionEntry entry) {
    // handle job completion
    final Materialization m = Preconditions.checkNotNull(materializationStore.getLastMaterialization(entry.getId()),
      "entry in REFRESHING/METADATA_REFRESH state has no materialization entries", entry.getId());
    Job job;
    try {
      job = jobsService.getJobFromStore(entry.getRefreshJobId());
    } catch (JobNotFoundException e) {
      // something's wrong, a REFRESHING/METADATA_REFRESH entry means we already submitted a job and we should be able
      // to retrieve it.
      // let's handle this as a failure to avoid hitting an infinite loop trying to handle this reflection entry
      m.setState(MaterializationState.FAILED)
        .setFailure(new Failure().setMessage(String.format("Couldn't retrieve refresh job %s", entry.getRefreshJobId().getId())));
      materializationStore.save(m);
      reportFailure(entry, ACTIVE);
      return;
    }

    switch (job.getJobAttempt().getState()) {
      case COMPLETED:
        logger.debug("refresh job {} for materialization {} completed successfully", job.getJobId().getId(), getId(m));
        handleSuccessfulRefreshJob(entry, m, job);
        break;
      case CANCELED:
        logger.debug("refresh job {} for materialization {} was cancelled", job.getJobId().getId(), getId(m));
        updateDependenciesIfPossible(entry, job.getJobAttempt());
        m.setState(MaterializationState.CANCELED);
        materializationStore.save(m);
        entry.setState(ACTIVE);
        reflectionStore.save(entry);
        break;
      case FAILED:
        logger.debug("refresh job {} for materialization {} failed", job.getJobId().getId(), getId(m));
        updateDependenciesIfPossible(entry, job.getJobAttempt());
        final String jobFailure = Optional.fromNullable(job.getJobAttempt().getInfo().getFailureInfo())
          .or("Materialization Job failed without reporting an error message");
        m.setState(MaterializationState.FAILED)
          .setFailure(new Failure().setMessage(jobFailure));
        materializationStore.save(m);
        reportFailure(entry, ACTIVE);
        break;
      default:
        // nothing to do for non terminal states
        break;
    }
  }

  private void updateDependenciesIfPossible(final ReflectionEntry entry, final JobAttempt jobAttempt) {
    if (dependencyManager.reflectionHasKnownDependencies(entry.getId())) {
      return;
    }

    try {
      final RefreshDecision decision = RefreshDoneHandler.getRefreshDecision(jobAttempt);
      RefreshDoneHandler.updateDependencies(entry.getId(), jobAttempt.getInfo(), decision, namespaceService, dependencyManager);
    } catch (Exception | AssertionError e) {
      logger.warn("Couldn't retrieve any dependency for reflection {}", getId(entry), e);
    }
  }

  /**
   * 1st pass: observe changes in the reflection user store
   * find all goals that were created or modified since last wakeup
   * for each identified description
   * if it doesn't have a corresponding reflection it's a NEW one
   * if it does and the version has changed it's an UPDATE
   * if it has a DELETED state, it's...well guess ;)
   *
   * @param lastWakeupTime previous wakeup time
   */
  private void handleGoals(long lastWakeupTime) {
    Iterable<ReflectionGoal> goals = userStore.getModifiedOrCreatedSince(lastWakeupTime);
    for (ReflectionGoal goal : goals) {
      try {
        handleGoal(goal);
      } catch (Exception e) {
        logger.error("Couldn't handle reflection goal {}", goal.getId().getId(), e);
      }
    }
  }

  private void handleDatasetDeletion(String datasetId, ReflectionId rId) {
    // make sure the corresponding dataset was not deleted
    if (namespaceService.findDatasetByUUID(datasetId) == null) {
      // dataset not found, mark goal as deleted
      logger.debug("dataset deleted for reflection {}", rId.getId());

      final ReflectionGoal goal = userStore.get(rId);
      if (goal != null) {
        try {
          userStore.save(goal.setState(ReflectionGoalState.DELETED));
          return;
        } catch (ConcurrentModificationException cme) {
          // someone's changed the reflection goal, we'll delete it next time the manager wakes up
          logger.debug("concurrent modification when trying mark reflection goal {} as deleted", rId.getId());
        }
      }

      final ExternalReflection externalReflection = externalReflectionStore.get(rId.getId());
      if (externalReflection != null) {
        externalReflectionStore.deleteExternalReflection(rId.getId());
        return;
      }

      // something wrong here
      throw new IllegalStateException("no reflection found for an existing reflection entry: " + rId.getId());
    }
  }

  private void handleGoal(ReflectionGoal goal) {
    final ReflectionEntry entry = reflectionStore.get(goal.getId());
    if (entry == null) {
      // no corresponding reflection, goal has been created or enabled
      if (goal.getState() == ReflectionGoalState.ENABLED) { // we still need to make sure user didn't create a disabled goal
        reflectionStore.save(create(goal));
      }
    } else if (!entry.getGoalVersion().equals(goal.getVersion())) {
      // descriptor changed
      logger.debug("reflection goal {} updated. state {} -> {}", getId(goal), entry.getState(), goal.getState());
      cancelRefreshJobIfAny(entry);
      final boolean enabled = goal.getState() == ReflectionGoalState.ENABLED;
      entry.setState(enabled ? UPDATE : DEPRECATE)
        .setName(goal.getName())
        .setGoalVersion(goal.getVersion());
      reflectionStore.save(entry);
    }
  }

  private void deleteReflection(ReflectionEntry entry) {
    logger.debug("deleting reflection {}", getId(entry));
    reflectionStore.delete(entry.getId());
    dependencyManager.delete(entry.getId());
  }

  private void deleteMaterialization(Materialization materialization) {
    if (Iterables.isEmpty(materializationStore.getRefreshesExclusivelyOwnedBy(materialization))) {
      logger.debug("materialization {} doesn't own any refresh, entry will be deleted without running a drop table", getId(materialization));
      materializationStore.delete(materialization.getId());
      return;
    }

    // set the materialization to DELETED so we don't try to delete it again
    materialization.setState(MaterializationState.DELETED);
    materializationStore.save(materialization);

    try {
      final String pathString = constructFullPath(getMaterializationPath(materialization));
      final String query = String.format("DROP TABLE IF EXISTS %s", pathString);
      MaterializationSummary materializationSummary = new MaterializationSummary()
        .setReflectionId(materialization.getReflectionId().getId())
        .setLayoutVersion(materialization.getReflectionGoalVersion().intValue())
        .setMaterializationId(materialization.getId().getId());
      jobsService.submitJob(
        JobRequest.newMaterializationJobBuilder(materializationSummary, SubstitutionSettings.of())
          .setSqlQuery(new SqlQuery(query, SYSTEM_USERNAME))
          .setQueryType(QueryType.ACCELERATOR_DROP)
          .build(),
        NoOpJobStatusListener.INSTANCE);
    } catch (Exception e) {
      logger.warn("failed to drop materialization {}", materialization.getId().getId(), e);
    }

  }

  private void deprecateMaterializations(ReflectionEntry entry) {
    // mark all materializations for the reflection as DEPRECATED
    // we only care about DONE materializations
    Iterable<Materialization> materializations = materializationStore.getAllDone(entry.getId());
    for (Materialization materialization : materializations) {
      deprecateMaterialization(materialization);
    }
  }

  private void deprecateMaterialization(Materialization materialization) {
    logger.debug("deprecating materialization {}/{}",
      materialization.getReflectionId().getId(), materialization.getId().getId());
    materialization.setState(MaterializationState.DEPRECATED);
    materializationStore.save(materialization);
    descriptorCache.invalidate(materialization.getId());
  }

  private void cancelRefreshJobIfAny(ReflectionEntry entry) {
    if (entry.getState() != REFRESHING && entry.getState() != METADATA_REFRESH) {
      return;
    }

    final Materialization m = Preconditions.checkNotNull(materializationStore.getLastMaterialization(entry.getId()),
      "reflection entry %s is in REFRESHING|METADATA_REFRESH state but has no materialization entry", entry.getId());

    try {
      logger.debug("cancelling materialization job {} for reflection {}", entry.getRefreshJobId().getId(), getId(entry));
      // even though the following method can block if the job's foreman is on a different node, it's not a problem here
      // as we always submit reflection jobs on the same node as the manager
      jobsService.cancel(SYSTEM_USERNAME, entry.getRefreshJobId());
    } catch (JobException e) {
      logger.warn("Failed to cancel refresh job updated reflection {}", getId(entry), e);
    }

    // mark the materialization as cancelled
    m.setState(MaterializationState.CANCELED);
    materializationStore.save(m);

    // we don't need to handle the job, if it did complete and wrote some data, they will eventually get deleted
    // when the materialization entry is deleted
  }

  private void handleSuccessfulRefreshJob(ReflectionEntry entry, Materialization materialization, Job job) {
    if (entry.getState() == REFRESHING) {
      refreshingJobSucceded(entry, materialization, job);
    } else if (entry.getState() == METADATA_REFRESH) {
      metadataRefreshJobSucceeded(entry, materialization);
    } else {
      throw new IllegalStateException("Unexpected state " + entry.getState());
    }
  }

  private void refreshingJobSucceded(ReflectionEntry entry, Materialization materialization, Job job) {

    try {
      final RefreshDoneHandler handler = new RefreshDoneHandler(entry, materialization, job, accelerationPlugin,
        namespaceService, materializationStore, dependencyManager, expansionHelper);
      final RefreshDecision decision = handler.handle();

      // no need to set the following attributes if we fail to handle the refresh
      // one could argue that we should still try to compute entry.dontGiveUp() if we were able to extract the dependencies
      // but if we really fail to handle a successful refresh job for 3 times in a row, the entry is a bad state
      entry.setRefreshMethod(decision.getAccelerationSettings().getMethod())
        .setRefreshField(decision.getAccelerationSettings().getRefreshField())
        .setDatasetHash(decision.getDatasetHash())
        .setDontGiveUp(dependencyManager.dontGiveUp(entry.getId()));
    } catch (Exception | AssertionError e) {
      logger.warn("failed to handle reflection {} job done", getId(entry), e);
      materialization.setState(MaterializationState.FAILED)
        .setFailure(new Failure().setMessage("Failed to handle successful refresh job " + job.getJobId().getId()));
    }

    // update the namespace metadata before saving information to the reflection store to avoid concurrent updates.
    if (materialization.getState() != MaterializationState.FAILED) {
      // lastSuccessfulRefresh is used to trigger refreshes on dependent reflections.
      // When an incremental refresh didn't write any data we still want to refresh its dependent reflections
      // as they may have failed the previous time and will still benefit from this refresh
      entry.setLastSuccessfulRefresh(System.currentTimeMillis());

      // even if the materialization didn't write any data it may still own refreshes if it's a non-initial incremental
      // otherwise we don't want to refresh an empty table as it will just fail
      if (!Iterables.isEmpty(materializationStore.getRefreshes(materialization))) {
        try {
          refreshMetadata(entry, materialization);
        } catch (Exception | AssertionError e) {
          logger.warn("failed to start a LOAD MATERIALIZATION job for {}", getId(materialization), e);
          materialization.setState(MaterializationState.FAILED)
            .setFailure(new Failure().setMessage("Failed to start a LOAD MATERIALIZATION job"));
        }
      } else {
        materialization.setState(MaterializationState.DONE);
        entry.setState(ACTIVE)
          .setNumFailures(0);
      }
    }

    if (materialization.getState() == MaterializationState.FAILED) {
      reportFailure(entry, ACTIVE);
    }

    materializationStore.save(materialization);
    reflectionStore.save(entry);
  }

  private void metadataRefreshJobSucceeded(ReflectionEntry entry, Materialization materialization) {

    try {
      descriptorCache.update(materialization);
    } catch (Exception | AssertionError e) {
      logger.warn("failed to update materialization cache for {}", getId(materialization), e);
      materialization.setState(MaterializationState.FAILED)
        .setFailure(new Failure().setMessage("Cache update failed"));
    }

    if (materialization.getState() == MaterializationState.FAILED) {
      // materialization failed
      reportFailure(entry, ACTIVE);
    } else {
      materialization.setState(MaterializationState.DONE);
      entry.setState(ACTIVE)
        .setNumFailures(0);
    }

    materializationStore.save(materialization);
    reflectionStore.save(entry);

  }

  private void refreshMetadata(ReflectionEntry entry, Materialization materialization) {
    final String sql = String.format("LOAD MATERIALIZATION METADATA '%s'", materialization.getId().getId());

    final SqlQuery query = new SqlQuery(sql, SYSTEM_USERNAME);
    NamespaceKey datasetPathList = new NamespaceKey(namespaceService.findDatasetByUUID(entry.getDatasetId()).getFullPathList());
    DatasetVersion datasetVersion = new DatasetVersion(entry.getDatasetVersion());
    MaterializationSummary materializationSummary = new MaterializationSummary()
      .setDatasetId(entry.getDatasetId())
      .setReflectionId(entry.getId().getId())
      .setLayoutVersion(entry.getVersion().intValue())
      .setMaterializationId(materialization.getId().getId());

    final Job job = jobsService.submitJob(
      JobRequest.newMaterializationJobBuilder(materializationSummary,
        new SubstitutionSettings(ImmutableList.<String>of()))
        .setSqlQuery(query)
        .setQueryType(QueryType.ACCELERATOR_CREATE)
        .setDatasetPath(datasetPathList)
        .setDatasetVersion(datasetVersion)
        .build(), new WakeUpManagerWhenJobDone(wakeUpCallback, "metadata refreshing job"));

    entry.setState(METADATA_REFRESH)
      .setRefreshJobId(job.getJobId());
    reflectionStore.save(entry);

    logger.debug("started job {} to load materialization metadata {}", job.getJobId().getId(), getId(materialization));
  }

  private void startRefresh(ReflectionEntry entry) {
    JobId refreshJobId;
    final long jobSubmissionTime = System.currentTimeMillis();
    try {
      refreshJobId = createStartHandler(entry).startJob(jobSubmissionTime);
    } catch (Exception | AssertionError e) {
      // we failed to start the refresh
      logger.warn("failed to refresh reflection {}", getId(entry), e);
      // did we create a RUNNING materialization entry ?
      final Materialization m = materializationStore.getRunningMaterialization(entry.getId());
      if (m != null) {
        // yes. Let's make sure we mark it as FAILED
        m.setState(MaterializationState.FAILED);
        materializationStore.save(m);
      }
      reportFailure(entry, ACTIVE);
      return;
    }

    entry.setState(REFRESHING)
      .setRefreshJobId(refreshJobId)
      .setLastSubmittedRefresh(jobSubmissionTime);
    reflectionStore.save(entry);

    logger.debug("Started job {} to materialize reflection {}", refreshJobId.getId(), entry.getId().getId());
  }

  private void reportFailure(ReflectionEntry entry, ReflectionState newState) {
    if (entry.getDontGiveUp()) {
      logger.debug("ignoring failure on reflection {} as it is marked as don't give up", getId(entry));
      entry.setState(newState)
        .setNumFailures(entry.getNumFailures() + 1);
      reflectionStore.save(entry);
      return;
    }

    final int numFailures = entry.getNumFailures() + 1;
    final long failuresThreshold = optionManager.getOption(LAYOUT_REFRESH_MAX_ATTEMPTS);
    final boolean markAsFailed = numFailures >= failuresThreshold;
    entry.setNumFailures(numFailures)
      .setState(markAsFailed ? FAILED : newState);
    reflectionStore.save(entry);

    if (markAsFailed) {
      logger.debug("reflection {} had {} consecutive failure and was marked with a FAILED state", getId(entry), numFailures);
      // remove the reflection from the dependency manager to update the dependencies of all its dependent reflections
      dependencyManager.delete(entry.getId());
    }
  }

  private RefreshStartHandler createStartHandler(ReflectionEntry entry) {
    return new RefreshStartHandler(entry, namespaceService, jobsService, materializationStore, wakeUpCallback);
  }

  private ReflectionEntry create(ReflectionGoal goal) {
    logger.debug("creating new reflection {}", goal.getId().getId());
    // retrieve reflection's dataset
    final DatasetConfig dataset = namespaceService.findDatasetByUUID(goal.getDatasetId());

    return new ReflectionEntry()
      .setId(goal.getId())
      .setGoalVersion(goal.getVersion())
      .setDatasetId(goal.getDatasetId())
      .setDatasetVersion(dataset.getVersion())
      .setState(REFRESH)
      .setType(goal.getType())
      .setName(goal.getName());
  }

}
