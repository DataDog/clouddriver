package com.netflix.spinnaker.clouddriver.kubernetes.v2.op.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.kubernetes.KubernetesCloudProvider
import com.netflix.spinnaker.clouddriver.kubernetes.security.KubernetesNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.kubernetes.v2.artifact.ArtifactTypes
import com.netflix.spinnaker.clouddriver.kubernetes.v2.artifact.KubernetesVersionedArtifactConverter
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.KubernetesResourcePropertyRegistry
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.KubernetesSpinnakerKindMap
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesDeployManifestDescription
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesKind
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesManifest
import com.netflix.spinnaker.clouddriver.kubernetes.v2.names.KubernetesManifestNamer
import com.netflix.spinnaker.clouddriver.kubernetes.v2.op.manifest.KubernetesDeployManifestOperation
import com.netflix.spinnaker.clouddriver.kubernetes.v2.security.KubernetesV2Credentials
import com.netflix.spinnaker.clouddriver.model.Manifest
import com.netflix.spinnaker.clouddriver.names.NamerRegistry
import com.netflix.spinnaker.kork.artifacts.model.Artifact
import com.netflix.spinnaker.moniker.Moniker
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import spock.lang.Specification

import java.util.concurrent.locks.Condition

class KubernetesStatefulSetHandlerSpec extends Specification {
  def objectMapper = new ObjectMapper()
  def yaml = new Yaml(new SafeConstructor())
  def handler = new KubernetesStatefulSetHandler()

  def IMAGE = "gcr.io/project/image"
  def ACCOUNT = "my-account"
  def CLUSTER_SIZE = 5
  def VERSION = "version"
  def NAMESPACE = "my-namespace"
  def NAME = "my-name"
  def KIND = KubernetesKind.STATEFUL_SET

  def STATEFUL_SET = """
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: $NAME
  namespace: $NAMESPACE
  labels:
    app: $NAME
    service: $NAME
spec:
  serviceName: $NAME-service
  podManagementPolicy: Parallel
  updateStrategy:
    type: RollingUpdate
    rollingUpdate:
      partition: $CLUSTER_SIZE
  replicas: $CLUSTER_SIZE
  selector:
    matchLabels:
      app: $NAME
      cluster: $NAME
  template:
    spec:    
      containers:
      - name: $NAME
        image: $IMAGE
        imagePullPolicy: Always
status:
 availableReplicas: $CLUSTER_SIZE
 observedGeneration: $CLUSTER_SIZE
 currentReplicas: $CLUSTER_SIZE
 readyReplicas: $CLUSTER_SIZE
 replicas: $CLUSTER_SIZE
 updatedReplicas: $CLUSTER_SIZE
 currentRevision: $NAME-$VERSION
 updateRevision: $NAME-$VERSION
"""

  def setupSpec() {
    TaskRepository.threadLocalTask.set(Mock(Task))
  }

  KubernetesDeployManifestOperation createMockDeployer(KubernetesV2Credentials credentials, String manifest) {
    def deployDescription = new KubernetesDeployManifestDescription()
      .setManifest(stringToManifest(manifest))
      .setMoniker(new Moniker())
      .setSource(KubernetesDeployManifestDescription.Source.text)

    def namedCredentialsMock = Mock(KubernetesNamedAccountCredentials)
    namedCredentialsMock.getCredentials() >> credentials
    namedCredentialsMock.getName() >> ACCOUNT
    deployDescription.setCredentials(namedCredentialsMock)

    credentials.deploy(_, _) >> null

    def statefulSetDeployer = new KubernetesStatefulSetHandler()
    statefulSetDeployer.versioned() >> true
    statefulSetDeployer.kind() >> KIND

    def registry = new KubernetesResourcePropertyRegistry(Collections.singletonList(statefulSetDeployer),
      new KubernetesSpinnakerKindMap())

    NamerRegistry.lookup().withProvider(KubernetesCloudProvider.ID)
      .withAccount(ACCOUNT)
      .setNamer(KubernetesManifest.class, new KubernetesManifestNamer())

    def deployOp = new KubernetesDeployManifestOperation(deployDescription, registry, null)

    return deployOp
  }

  KubernetesManifest stringToManifest(String input) {
    return objectMapper.convertValue(yaml.load(input), KubernetesManifest)
  }

  void "check for stable state by default"() {
    setup:
    def credentialsMock = Mock(KubernetesV2Credentials)
    credentialsMock.getDefaultNamespace() >> NAMESPACE
    def deployOp = createMockDeployer(credentialsMock, STATEFUL_SET)
    def stable = new Manifest.Status.Condition(state: true, message: null)

    when:
    def deployResult = deployOp.operate([])

    then:
    deployResult.manifestNamesByNamespace[NAMESPACE].size() == 1
    deployResult.manifestNamesByNamespace[NAMESPACE][0] == "$KIND $NAME"
    handler.status(deployResult.getManifests().first()).stable == stable
  }
}
