package com.netflix.spinnaker.clouddriver.kubernetes.v2.op.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.kubernetes.KubernetesCloudProvider
import com.netflix.spinnaker.clouddriver.kubernetes.security.KubernetesNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.KubernetesResourcePropertyRegistry
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.KubernetesSpinnakerKindMap
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesDeployManifestDescription
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesKind
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesManifest
import com.netflix.spinnaker.clouddriver.kubernetes.v2.names.KubernetesManifestNamer
import com.netflix.spinnaker.clouddriver.kubernetes.v2.op.manifest.KubernetesDeployManifestOperation
import com.netflix.spinnaker.clouddriver.kubernetes.v2.security.KubernetesV2Credentials
import com.netflix.spinnaker.clouddriver.names.NamerRegistry
import com.netflix.spinnaker.moniker.Moniker
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import spock.lang.Specification


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

  def STATEFUL_SET_BASE = """
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: $NAME
  namespace: $NAMESPACE
  generation: 1
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
    statefulSetDeployer.versioned() >> false
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

  def "statefulset stable state is null if generation > observedGeneration"() {
    setup:
    def statusYaml = """
status:
 observedGeneration: 0
"""
    def credentialsMock = Mock(KubernetesV2Credentials)
    credentialsMock.getDefaultNamespace() >> NAMESPACE
    def deployOp = createMockDeployer(credentialsMock, STATEFUL_SET_BASE + statusYaml)

    when:
    def deployResult = deployOp.operate([])
    def status = handler.status(deployResult.getManifests().first())

    then:
    status.stable == null
  }

  def "wait for at least the desired replica count to be met"() {
    setup:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1
 currentReplicas: 0
 readyReplicas: 0
 replicas: 0
 updatedReplicas: 0
 currentRevision: $NAME-$VERSION
 updateRevision: $NAME-$VERSION
"""
    def credentialsMock = Mock(KubernetesV2Credentials)
    credentialsMock.getDefaultNamespace() >> NAMESPACE
    def deployOp = createMockDeployer(credentialsMock, STATEFUL_SET_BASE + statusYaml)

    when:
    def deployResult = deployOp.operate([])
    def status = handler.status(deployResult.getManifests().first())

    then:
    !status.stable.state
    status.stable.message == "Waiting for at least the desired replica count to be met"
  }

  def "wait for the updated revision to match the current revision"() {
    setup:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1
 currentReplicas: 0
 readyReplicas: 0
 replicas: 5
 updatedReplicas: 0
 currentRevision: $NAME-new-my-version
 updateRevision: $NAME-$VERSION
"""
    def credentialsMock = Mock(KubernetesV2Credentials)
    credentialsMock.getDefaultNamespace() >> NAMESPACE
    def deployOp = createMockDeployer(credentialsMock, STATEFUL_SET_BASE + statusYaml)

    when:
    def deployResult = deployOp.operate([])
    def status = handler.status(deployResult.getManifests().first())

    then:
    !status.stable.state
    status.stable.message == "Waiting for the updated revision to match the current revision"
  }

  def "wait for all updated replicas to be scheduled"() {
    setup:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1
 currentReplicas: 4
 readyReplicas: 0
 replicas: 5
 updatedReplicas: 0
 currentRevision: $NAME-$VERSION
 updateRevision: $NAME-$VERSION
"""
    def credentialsMock = Mock(KubernetesV2Credentials)
    credentialsMock.getDefaultNamespace() >> NAMESPACE
    def deployOp = createMockDeployer(credentialsMock, STATEFUL_SET_BASE + statusYaml)

    when:
    def deployResult = deployOp.operate([])
    def status = handler.status(deployResult.getManifests().first())

    then:
    !status.stable.state
    status.stable.message == "Waiting for all updated replicas to be scheduled"
  }

  def "wait for all updated replicas to be ready"() {
    setup:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1
 currentReplicas: 5
 readyReplicas: 4
 replicas: 5
 updatedReplicas: 0
 currentRevision: $NAME-$VERSION
 updateRevision: $NAME-$VERSION
"""
    def credentialsMock = Mock(KubernetesV2Credentials)
    credentialsMock.getDefaultNamespace() >> NAMESPACE
    def deployOp = createMockDeployer(credentialsMock, STATEFUL_SET_BASE + statusYaml)

    when:
    def deployResult = deployOp.operate([])
    def status = handler.status(deployResult.getManifests().first())

    then:
    !status.stable.state
    status.stable.message == "Waiting for all updated replicas to be ready"
  }
}
