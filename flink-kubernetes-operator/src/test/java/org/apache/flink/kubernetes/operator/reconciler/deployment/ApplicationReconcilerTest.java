/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.operator.reconciler.deployment;

import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.SchedulerExecutionMode;
import org.apache.flink.kubernetes.operator.TestUtils;
import org.apache.flink.kubernetes.operator.TestingFlinkService;
import org.apache.flink.kubernetes.operator.TestingStatusRecorder;
import org.apache.flink.kubernetes.operator.config.FlinkConfigManager;
import org.apache.flink.kubernetes.operator.config.KubernetesOperatorConfigOptions;
import org.apache.flink.kubernetes.operator.crd.FlinkDeployment;
import org.apache.flink.kubernetes.operator.crd.spec.FlinkDeploymentSpec;
import org.apache.flink.kubernetes.operator.crd.spec.FlinkVersion;
import org.apache.flink.kubernetes.operator.crd.spec.JobState;
import org.apache.flink.kubernetes.operator.crd.spec.KubernetesDeploymentMode;
import org.apache.flink.kubernetes.operator.crd.spec.UpgradeMode;
import org.apache.flink.kubernetes.operator.crd.status.FlinkDeploymentStatus;
import org.apache.flink.kubernetes.operator.crd.status.JobManagerDeploymentStatus;
import org.apache.flink.kubernetes.operator.crd.status.JobStatus;
import org.apache.flink.kubernetes.operator.crd.status.ReconciliationState;
import org.apache.flink.kubernetes.operator.crd.status.Savepoint;
import org.apache.flink.kubernetes.operator.crd.status.SavepointTriggerType;
import org.apache.flink.kubernetes.operator.exception.DeploymentFailedException;
import org.apache.flink.kubernetes.operator.reconciler.ReconciliationUtils;
import org.apache.flink.kubernetes.operator.utils.EventRecorder;
import org.apache.flink.kubernetes.operator.utils.SavepointUtils;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.highavailability.JobResultStoreOptions;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** @link JobStatusObserver unit tests */
@EnableKubernetesMockClient(crud = true)
public class ApplicationReconcilerTest {

    private KubernetesClient kubernetesClient;
    private final FlinkConfigManager configManager = new FlinkConfigManager(new Configuration());
    private TestingFlinkService flinkService;
    private ApplicationReconciler reconciler;
    private Context<FlinkDeployment> context;

    @BeforeEach
    public void before() {
        kubernetesClient.resource(TestUtils.buildApplicationCluster()).createOrReplace();
        var eventRecorder = new EventRecorder(kubernetesClient, (r, e) -> {});
        var statusRecoder = new TestingStatusRecorder<FlinkDeployment, FlinkDeploymentStatus>();
        flinkService = new TestingFlinkService(kubernetesClient);
        context = flinkService.getContext();
        reconciler =
                new ApplicationReconciler(
                        kubernetesClient,
                        flinkService,
                        configManager,
                        eventRecorder,
                        statusRecoder);
    }

    @ParameterizedTest
    @EnumSource(FlinkVersion.class)
    public void testUpgrade(FlinkVersion flinkVersion) throws Exception {
        FlinkDeployment deployment = TestUtils.buildApplicationCluster(flinkVersion);

        reconciler.reconcile(deployment, context);
        var runningJobs = flinkService.listJobs();
        verifyAndSetRunningJobsToStatus(deployment, runningJobs);

        // Test stateless upgrade
        FlinkDeployment statelessUpgrade = ReconciliationUtils.clone(deployment);
        statelessUpgrade.getSpec().getJob().setUpgradeMode(UpgradeMode.STATELESS);
        statelessUpgrade.getSpec().getFlinkConfiguration().put("new", "conf");
        reconciler.reconcile(statelessUpgrade, context);

        assertEquals(0, flinkService.getRunningCount());

        reconciler.reconcile(statelessUpgrade, context);

        runningJobs = flinkService.listJobs();
        assertEquals(1, flinkService.getRunningCount());
        assertNull(runningJobs.get(0).f0);

        deployment
                .getStatus()
                .getJobStatus()
                .setJobId(runningJobs.get(0).f1.getJobId().toHexString());

        // Test stateful upgrade
        FlinkDeployment statefulUpgrade = ReconciliationUtils.clone(deployment);
        statefulUpgrade.getSpec().getJob().setUpgradeMode(UpgradeMode.SAVEPOINT);
        statefulUpgrade.getSpec().getFlinkConfiguration().put("new", "conf2");

        reconciler.reconcile(statefulUpgrade, context);

        assertEquals(0, flinkService.getRunningCount());

        reconciler.reconcile(statefulUpgrade, context);

        runningJobs = flinkService.listJobs();
        assertEquals(1, flinkService.getRunningCount());
        assertEquals("savepoint_0", runningJobs.get(0).f0);
        assertEquals(
                SavepointTriggerType.UPGRADE,
                statefulUpgrade
                        .getStatus()
                        .getJobStatus()
                        .getSavepointInfo()
                        .getLastSavepoint()
                        .getTriggerType());

        deployment.getSpec().getJob().setUpgradeMode(UpgradeMode.LAST_STATE);
        deployment.getSpec().setRestartNonce(100L);
        deployment
                .getStatus()
                .getReconciliationStatus()
                .setLastStableSpec(
                        deployment.getStatus().getReconciliationStatus().getLastReconciledSpec());
        flinkService.setHaDataAvailable(false);
        deployment.getStatus().getJobStatus().setState("RECONCILING");

        try {
            deployment
                    .getStatus()
                    .setJobManagerDeploymentStatus(JobManagerDeploymentStatus.MISSING);
            reconciler.reconcile(deployment, context);
            fail();
        } catch (DeploymentFailedException expected) {
        }

        try {
            deployment.getStatus().setJobManagerDeploymentStatus(JobManagerDeploymentStatus.ERROR);
            reconciler.reconcile(deployment, context);
            fail();
        } catch (DeploymentFailedException expected) {
        }

        flinkService.clear();
        deployment.getSpec().getJob().setUpgradeMode(UpgradeMode.LAST_STATE);
        deployment.getSpec().setRestartNonce(200L);
        flinkService.setHaDataAvailable(false);
        deployment
                .getStatus()
                .getJobStatus()
                .getSavepointInfo()
                .setLastSavepoint(Savepoint.of("finished_sp", SavepointTriggerType.UPGRADE));
        deployment.getStatus().getJobStatus().setState("FINISHED");
        deployment.getStatus().setJobManagerDeploymentStatus(JobManagerDeploymentStatus.READY);
        reconciler.reconcile(deployment, context);
        reconciler.reconcile(deployment, context);

        assertEquals(1, flinkService.getRunningCount());
        assertEquals("finished_sp", runningJobs.get(0).f0);
    }

    @ParameterizedTest
    @EnumSource(UpgradeMode.class)
    public void testUpgradeBeforeReachingStableSpec(UpgradeMode upgradeMode) throws Exception {
        flinkService.setHaDataAvailable(false);

        final FlinkDeployment deployment = TestUtils.buildApplicationCluster();

        reconciler.reconcile(deployment, context);
        assertEquals(
                JobManagerDeploymentStatus.DEPLOYING,
                deployment.getStatus().getJobManagerDeploymentStatus());

        // Ready for spec changes, the reconciliation should be performed
        final String newImage = "new-image-1";
        deployment.getSpec().getJob().setUpgradeMode(upgradeMode);
        deployment.getSpec().setImage(newImage);
        reconciler.reconcile(deployment, context);
        if (!UpgradeMode.STATELESS.equals(upgradeMode)) {
            assertNull(deployment.getStatus().getReconciliationStatus().getLastReconciledSpec());
            assertEquals(
                    ReconciliationState.UPGRADING,
                    deployment.getStatus().getReconciliationStatus().getState());
            reconciler.reconcile(deployment, context);
        }
        assertEquals(
                newImage,
                deployment
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getImage());
    }

    @Test
    public void testUpgradeModeChangeFromSavepointToLastState() throws Exception {
        final String expectedSavepointPath = "savepoint_0";
        final FlinkDeployment deployment = TestUtils.buildApplicationCluster();

        reconciler.reconcile(deployment, context);
        var runningJobs = flinkService.listJobs();
        verifyAndSetRunningJobsToStatus(deployment, runningJobs);

        // Suspend FlinkDeployment with savepoint upgrade mode
        deployment.getSpec().getJob().setUpgradeMode(UpgradeMode.SAVEPOINT);
        deployment.getSpec().getJob().setState(JobState.SUSPENDED);
        deployment.getSpec().setImage("new-image-1");

        reconciler.reconcile(deployment, context);
        assertEquals(0, flinkService.getRunningCount());
        assertEquals(
                org.apache.flink.api.common.JobStatus.FINISHED.name(),
                deployment.getStatus().getJobStatus().getState());

        assertEquals(
                expectedSavepointPath,
                deployment
                        .getStatus()
                        .getJobStatus()
                        .getSavepointInfo()
                        .getLastSavepoint()
                        .getLocation());

        deployment.getSpec().getJob().setUpgradeMode(UpgradeMode.LAST_STATE);
        deployment.getSpec().getJob().setState(JobState.RUNNING);
        deployment.getSpec().setImage("new-image-2");

        reconciler.reconcile(deployment, context);
        runningJobs = flinkService.listJobs();
        assertEquals(1, flinkService.getRunningCount());
        assertEquals(expectedSavepointPath, runningJobs.get(0).f0);
    }

    @Test
    public void triggerSavepoint() throws Exception {
        FlinkDeployment deployment = TestUtils.buildApplicationCluster();

        reconciler.reconcile(deployment, context);
        var runningJobs = flinkService.listJobs();
        verifyAndSetRunningJobsToStatus(deployment, runningJobs);
        assertFalse(SavepointUtils.savepointInProgress(deployment.getStatus().getJobStatus()));

        FlinkDeployment spDeployment = ReconciliationUtils.clone(deployment);

        // don't trigger if nonce is missing
        reconciler.reconcile(spDeployment, context);
        assertFalse(SavepointUtils.savepointInProgress(spDeployment.getStatus().getJobStatus()));

        // trigger when nonce is defined
        spDeployment
                .getSpec()
                .getJob()
                .setSavepointTriggerNonce(ThreadLocalRandom.current().nextLong());
        reconciler.reconcile(spDeployment, context);
        assertNull(
                spDeployment
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getSavepointTriggerNonce());
        assertEquals(
                "trigger_0",
                spDeployment.getStatus().getJobStatus().getSavepointInfo().getTriggerId());
        assertTrue(SavepointUtils.savepointInProgress(spDeployment.getStatus().getJobStatus()));

        // don't trigger when savepoint is in progress
        reconciler.reconcile(spDeployment, context);
        assertEquals(
                "trigger_0",
                spDeployment.getStatus().getJobStatus().getSavepointInfo().getTriggerId());
        assertEquals(
                SavepointTriggerType.MANUAL,
                spDeployment.getStatus().getJobStatus().getSavepointInfo().getTriggerType());

        spDeployment.getStatus().getJobStatus().getSavepointInfo().resetTrigger();
        ReconciliationUtils.updateLastReconciledSavepointTriggerNonce(
                spDeployment.getStatus().getJobStatus().getSavepointInfo(), spDeployment);

        // don't trigger when nonce is the same
        reconciler.reconcile(spDeployment, context);
        assertFalse(SavepointUtils.savepointInProgress(spDeployment.getStatus().getJobStatus()));

        spDeployment.getStatus().getJobStatus().getSavepointInfo().resetTrigger();
        ReconciliationUtils.updateLastReconciledSavepointTriggerNonce(
                spDeployment.getStatus().getJobStatus().getSavepointInfo(), spDeployment);

        // trigger when new nonce is defined
        spDeployment
                .getSpec()
                .getJob()
                .setSavepointTriggerNonce(ThreadLocalRandom.current().nextLong());
        reconciler.reconcile(spDeployment, context);
        assertEquals(
                "trigger_1",
                spDeployment.getStatus().getJobStatus().getSavepointInfo().getTriggerId());
        assertEquals(
                SavepointTriggerType.MANUAL,
                spDeployment.getStatus().getJobStatus().getSavepointInfo().getTriggerType());

        // re-trigger after reset
        spDeployment.getStatus().getJobStatus().getSavepointInfo().resetTrigger();
        reconciler.reconcile(spDeployment, context);
        assertEquals(
                "trigger_2",
                spDeployment.getStatus().getJobStatus().getSavepointInfo().getTriggerId());
        assertEquals(
                SavepointTriggerType.MANUAL,
                spDeployment.getStatus().getJobStatus().getSavepointInfo().getTriggerType());

        spDeployment.getStatus().getJobStatus().getSavepointInfo().resetTrigger();
        ReconciliationUtils.updateLastReconciledSavepointTriggerNonce(
                spDeployment.getStatus().getJobStatus().getSavepointInfo(), spDeployment);

        // don't trigger nonce is cleared
        spDeployment.getSpec().getJob().setSavepointTriggerNonce(null);
        reconciler.reconcile(spDeployment, context);
        assertFalse(SavepointUtils.savepointInProgress(spDeployment.getStatus().getJobStatus()));

        reconciler.reconcile(spDeployment, context);
        assertFalse(SavepointUtils.savepointInProgress(spDeployment.getStatus().getJobStatus()));

        spDeployment
                .getSpec()
                .getFlinkConfiguration()
                .put(KubernetesOperatorConfigOptions.PERIODIC_SAVEPOINT_INTERVAL.key(), "1");
        reconciler.reconcile(spDeployment, context);
        assertTrue(SavepointUtils.savepointInProgress(spDeployment.getStatus().getJobStatus()));
    }

    @Test
    public void testUpgradeModeChangedToLastStateShouldTriggerSavepointWhileHADisabled()
            throws Exception {
        flinkService.setHaDataAvailable(false);

        final FlinkDeployment deployment = TestUtils.buildApplicationCluster();
        deployment.getSpec().getFlinkConfiguration().remove(HighAvailabilityOptions.HA_MODE.key());

        reconciler.reconcile(deployment, context);
        assertEquals(
                JobManagerDeploymentStatus.DEPLOYING,
                deployment.getStatus().getJobManagerDeploymentStatus());

        // Not ready for spec changes, the reconciliation is not performed
        final String newImage = "new-image-1";
        deployment.getSpec().getJob().setUpgradeMode(UpgradeMode.LAST_STATE);
        deployment.getSpec().setImage(newImage);
        reconciler.reconcile(deployment, context);
        reconciler.reconcile(deployment, context);
        assertNull(flinkService.listJobs().get(0).f0);
        assertNotEquals(
                newImage,
                deployment
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getImage());

        // Ready for spec changes, the reconciliation should be performed
        verifyAndSetRunningJobsToStatus(deployment, flinkService.listJobs());
        reconciler.reconcile(deployment, context);
        reconciler.reconcile(deployment, context);
        assertEquals(
                newImage,
                deployment
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getImage());
        // Upgrade mode changes from stateless to last-state should trigger a savepoint
        final String expectedSavepointPath = "savepoint_0";
        var runningJobs = flinkService.listJobs();
        assertEquals(expectedSavepointPath, runningJobs.get(0).f0);
    }

    @Test
    public void testUpgradeModeChangedToLastStateShouldNotTriggerSavepointWhileHAEnabled()
            throws Exception {
        final FlinkDeployment deployment = TestUtils.buildApplicationCluster();

        reconciler.reconcile(deployment, context);
        assertNotEquals(
                UpgradeMode.LAST_STATE,
                deployment
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getUpgradeMode());

        final String newImage = "new-image-1";
        deployment.getSpec().getJob().setUpgradeMode(UpgradeMode.LAST_STATE);
        deployment.getSpec().setImage(newImage);
        verifyAndSetRunningJobsToStatus(deployment, flinkService.listJobs());
        reconciler.reconcile(deployment, context);
        reconciler.reconcile(deployment, context);
        assertEquals(
                newImage,
                deployment
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getImage());
        // Upgrade mode changes from stateless to last-state while HA enabled previously should not
        // trigger a savepoint
        assertNull(flinkService.listJobs().get(0).f0);
    }

    @Test
    public void triggerRestart() throws Exception {
        FlinkDeployment deployment = TestUtils.buildApplicationCluster();

        reconciler.reconcile(deployment, context);
        var runningJobs = flinkService.listJobs();
        verifyAndSetRunningJobsToStatus(deployment, runningJobs);

        // Test restart job
        FlinkDeployment restartJob = ReconciliationUtils.clone(deployment);
        restartJob.getSpec().setRestartNonce(1L);
        reconciler.reconcile(restartJob, context);
        assertEquals(
                JobState.SUSPENDED,
                restartJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getState());
        assertEquals(0, flinkService.getRunningCount());

        reconciler.reconcile(restartJob, context);
        assertEquals(
                JobState.RUNNING,
                restartJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getState());
        assertEquals(1, flinkService.getRunningCount());
        assertEquals(
                1L,
                restartJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getRestartNonce());
    }

    private void verifyAndSetRunningJobsToStatus(
            FlinkDeployment deployment,
            List<Tuple3<String, JobStatusMessage, Configuration>> runningJobs) {
        assertEquals(1, runningJobs.size());
        assertNull(runningJobs.get(0).f0);
        deployment
                .getStatus()
                .setJobStatus(
                        new JobStatus()
                                .toBuilder()
                                .jobId(runningJobs.get(0).f1.getJobId().toHexString())
                                .jobName(runningJobs.get(0).f1.getJobName())
                                .state("RUNNING")
                                .build());
        deployment.getStatus().setJobManagerDeploymentStatus(JobManagerDeploymentStatus.READY);
    }

    @Test
    public void testJobUpgradeIgnorePendingSavepoint() throws Exception {
        FlinkDeployment deployment = TestUtils.buildApplicationCluster();
        reconciler.reconcile(deployment, context);
        var runningJobs = flinkService.listJobs();
        verifyAndSetRunningJobsToStatus(deployment, runningJobs);

        FlinkDeployment spDeployment = ReconciliationUtils.clone(deployment);
        spDeployment
                .getSpec()
                .getJob()
                .setSavepointTriggerNonce(ThreadLocalRandom.current().nextLong());
        reconciler.reconcile(spDeployment, context);
        assertEquals(
                "trigger_0",
                spDeployment.getStatus().getJobStatus().getSavepointInfo().getTriggerId());
        assertEquals(JobState.RUNNING.name(), spDeployment.getStatus().getJobStatus().getState());

        // Force upgrade when savepoint is in progress.
        spDeployment
                .getSpec()
                .getFlinkConfiguration()
                .put(
                        KubernetesOperatorConfigOptions.JOB_UPGRADE_IGNORE_PENDING_SAVEPOINT.key(),
                        "true");
        spDeployment.getSpec().setImage("flink:greatest");
        reconciler.reconcile(spDeployment, context);
        assertEquals(
                "trigger_0",
                spDeployment.getStatus().getJobStatus().getSavepointInfo().getTriggerId());
        assertEquals(
                org.apache.flink.api.common.JobStatus.FINISHED.name(),
                spDeployment.getStatus().getJobStatus().getState());
    }

    @Test
    public void testRandomJobResultStorePath() throws Exception {
        FlinkDeployment flinkApp = TestUtils.buildApplicationCluster();
        final String haStoragePath = "file:///flink-data/ha";
        flinkApp.getSpec()
                .getFlinkConfiguration()
                .put(HighAvailabilityOptions.HA_STORAGE_PATH.key(), haStoragePath);

        ObjectMeta deployMeta = flinkApp.getMetadata();
        FlinkDeploymentStatus status = flinkApp.getStatus();
        FlinkDeploymentSpec spec = flinkApp.getSpec();
        Configuration deployConfig = configManager.getDeployConfig(deployMeta, spec);

        status.getJobStatus().setState(org.apache.flink.api.common.JobStatus.FINISHED.name());
        status.setJobManagerDeploymentStatus(JobManagerDeploymentStatus.READY);
        reconciler.deploy(flinkApp, spec, status, context, deployConfig, Optional.empty(), false);

        String path1 = deployConfig.get(JobResultStoreOptions.STORAGE_PATH);
        Assertions.assertTrue(path1.startsWith(haStoragePath));

        status.getJobStatus().setState(org.apache.flink.api.common.JobStatus.FINISHED.name());
        status.setJobManagerDeploymentStatus(JobManagerDeploymentStatus.READY);
        reconciler.deploy(flinkApp, spec, status, context, deployConfig, Optional.empty(), false);
        String path2 = deployConfig.get(JobResultStoreOptions.STORAGE_PATH);
        Assertions.assertTrue(path2.startsWith(haStoragePath));
        assertNotEquals(path1, path2);
    }

    @Test
    public void testAlwaysSavepointOnFlinkVersionChange() throws Exception {
        var deployment = TestUtils.buildApplicationCluster(FlinkVersion.v1_14);
        deployment.getSpec().getJob().setUpgradeMode(UpgradeMode.LAST_STATE);

        reconciler.reconcile(deployment, context);

        deployment.getSpec().setFlinkVersion(FlinkVersion.v1_15);

        var reconStatus = deployment.getStatus().getReconciliationStatus();

        // Do not trigger update until running
        reconciler.reconcile(deployment, context);
        assertEquals(ReconciliationState.DEPLOYED, reconStatus.getState());

        deployment.getStatus().getJobStatus().setState(JobState.RUNNING.name());
        deployment
                .getStatus()
                .getJobStatus()
                .setJobId(flinkService.listJobs().get(0).f1.getJobId().toHexString());

        reconciler.reconcile(deployment, context);
        assertEquals(ReconciliationState.UPGRADING, reconStatus.getState());
        assertEquals(
                UpgradeMode.SAVEPOINT,
                reconStatus.deserializeLastReconciledSpec().getJob().getUpgradeMode());
    }

    @Test
    public void testScaleWithReactiveModeDisabled() throws Exception {
        FlinkDeployment deployment = TestUtils.buildApplicationCluster();

        reconciler.reconcile(deployment, context);
        verifyAndSetRunningJobsToStatus(deployment, flinkService.listJobs());
        deployment.getSpec().getJob().setParallelism(100);
        reconciler.reconcile(deployment, context);
        assertEquals(
                JobState.SUSPENDED,
                deployment
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getState());
    }

    @Test
    public void testScaleWithReactiveModeEnabled() throws Exception {
        FlinkDeployment deployment = TestUtils.buildApplicationCluster();
        deployment.getSpec().setMode(KubernetesDeploymentMode.STANDALONE);
        deployment
                .getSpec()
                .getFlinkConfiguration()
                .put(
                        JobManagerOptions.SCHEDULER_MODE.key(),
                        SchedulerExecutionMode.REACTIVE.name());

        reconciler.reconcile(deployment, context);
        verifyAndSetRunningJobsToStatus(deployment, flinkService.listJobs());

        // the default.parallelism is always ignored
        deployment
                .getSpec()
                .getFlinkConfiguration()
                .put(CoreOptions.DEFAULT_PARALLELISM.key(), "100");
        reconciler.reconcile(deployment, context);
        assertEquals(
                JobState.RUNNING,
                deployment
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getState());
        assertEquals(0, flinkService.getDesiredReplicas());

        deployment.getSpec().getJob().setParallelism(4);
        reconciler.reconcile(deployment, context);
        assertEquals(
                JobState.RUNNING,
                deployment
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getState());
        assertEquals(2, flinkService.getDesiredReplicas());

        deployment.getSpec().getJob().setParallelism(8);
        reconciler.reconcile(deployment, context);
        assertEquals(
                JobState.RUNNING,
                deployment
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getState());
        assertEquals(4, flinkService.getDesiredReplicas());
    }
}
