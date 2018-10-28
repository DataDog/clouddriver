package com.netflix.spinnaker.clouddriver.kubernetes.v2.op.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesKind
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesManifest
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

  def statefulSetManifestWithPartition(Integer partition = CLUSTER_SIZE) {

    return """
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: $NAME
  namespace: $NAMESPACE
  generation: 1.0
  labels:
    app: $NAME
    service: $NAME
spec:
  serviceName: $NAME-service
  podManagementPolicy: Parallel
  updateStrategy:
    type: RollingUpdate
    rollingUpdate:
      partition: $partition
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
  }

  def statefulSetManifest = """
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: $NAME
  namespace: $NAMESPACE
  generation: 1.0
  labels:
    app: $NAME
    service: $NAME
spec:
  serviceName: $NAME-service
  podManagementPolicy: Parallel
  updateStrategy:
    type: RollingUpdate
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

  KubernetesManifest stringToManifest(String input) {
    return objectMapper.convertValue(yaml.load(input), KubernetesManifest)
  }

  def "statefulset stable state is null if manifest.isNewerThanObservedGeneration()"() {
    when:
    def statusYaml = """
status:
 observedGeneration: 0.0
"""
    def manifest = stringToManifest(statefulSetManifest + statusYaml)
    def status = handler.status(manifest)

    then:
    status.stable == null
  }

  def "wait for at least the desired replica count to be met"() {
    when:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1.0
 currentReplicas: 0
 readyReplicas: 0
 replicas: 0
 updatedReplicas: 0
 currentRevision: $NAME-$VERSION
 updateRevision: $NAME-$VERSION
"""
    def manifest = stringToManifest(statefulSetManifest + statusYaml)
    def status = handler.status(manifest)

    then:
    !status.stable.state
    status.stable.message == "Waiting for at least the desired replica count to be met"
  }

  def "wait for the updated revision to match the current revision"() {
    when:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1.0
 currentReplicas: 5
 readyReplicas: 5
 replicas: 5
 updatedReplicas: 5
 currentRevision: $NAME-new-my-version
 updateRevision: $NAME-$VERSION
"""
    def manifest = stringToManifest(statefulSetManifest + statusYaml)
    def status = handler.status(manifest)

    then:
    !status.stable.state
    status.stable.message == "Waiting for the updated revision to match the current revision"
  }

  def "wait for all updated replicas to be scheduled"() {
    when:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1.0
 currentReplicas: 4
 readyReplicas: 5
 replicas: 5
 currentRevision: $NAME-$VERSION
 updateRevision: $NAME-$VERSION
"""
    def manifest = stringToManifest(statefulSetManifest + statusYaml)
    def status = handler.status(manifest)

    then:
    !status.stable.state
    status.stable.message == "Waiting for all updated replicas to be scheduled"
  }

  def "wait for all updated replicas to be ready"() {
    when:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1.0
 currentReplicas: 5
 readyReplicas: 4
 replicas: 5
 updatedReplicas: 0
 currentRevision: $NAME-$VERSION
 updateRevision: $NAME-$VERSION
"""
    def manifest = stringToManifest(statefulSetManifestWithPartition() + statusYaml)
    def status = handler.status(manifest)

    then:
    !status.stable.state
    status.stable.message == "Waiting for all updated replicas to be ready"
  }

  def "wait for partitioned roll out to finish"() {
    when:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1.0
 currentReplicas: 5
 readyReplicas: 5
 replicas: 5
 updatedReplicas: 0
 currentRevision: $NAME-$VERSION
 updateRevision: $NAME-$VERSION
"""
    def manifest = stringToManifest(statefulSetManifestWithPartition(1) + statusYaml)
    def status = handler.status(manifest)

    then:
    !status.stable.state
    status.stable.message == "Waiting for partitioned roll out to finish"
  }

  def "wait for updated replicas"() {
    when:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1.0
 currentReplicas: 5
 readyReplicas: 5
 replicas: 5
 updatedReplicas: 3
 currentRevision: $NAME-$VERSION
 updateRevision: $NAME-$VERSION
"""
    def manifest = stringToManifest(statefulSetManifestWithPartition(1) + statusYaml)
    def status = handler.status(manifest)

    then:
    !status.stable.state
    status.stable.message == "Waiting for partitioned roll out to finish"
  }

  def "wait for partitioned roll out complete"() {
    when:
    def statusYaml = """
status:
 availableReplicas: 0
 observedGeneration: 1.0
 currentReplicas: 5
 readyReplicas: 5
 replicas: 5
 updatedReplicas: 5
 currentRevision: $NAME-$VERSION
 updateRevision: $NAME-$VERSION
"""
    def manifest = stringToManifest(statefulSetManifestWithPartition(5) + statusYaml)
    def status = handler.status(manifest)

    then:
    status.stable.state
    status.stable.message == "Partitioned roll out complete"
  }

}
