/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.k8s.submit

import java.io.File

import com.google.common.base.Charsets
import com.google.common.io.Files

import org.apache.spark.{SparkConf, SparkFunSuite}
import org.apache.spark.deploy.k8s.{KubernetesConf, KubernetesDriverSpec, KubernetesDriverSpecificConf}
import org.apache.spark.deploy.k8s.features.{BasicDriverFeatureStep, DriverKubernetesCredentialsFeatureStep, DriverServiceFeatureStep, KubernetesFeaturesTestUtils, MountLocalFilesFeatureStep, MountSecretsFeatureStep}
import org.apache.spark.util.Utils

class KubernetesDriverBuilderSuite extends SparkFunSuite {

  private val BASIC_STEP_TYPE = "basic"
  private val CREDENTIALS_STEP_TYPE = "credentials"
  private val SERVICE_STEP_TYPE = "service"
  private val SECRETS_STEP_TYPE = "mount-secrets"
  private val MOUNT_LOCAL_FILES_STEP_TYPE = "mount-local-files"

  private val basicFeatureStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    BASIC_STEP_TYPE, classOf[BasicDriverFeatureStep])

  private val credentialsStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    CREDENTIALS_STEP_TYPE, classOf[DriverKubernetesCredentialsFeatureStep])

  private val serviceStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    SERVICE_STEP_TYPE, classOf[DriverServiceFeatureStep])

  private val secretsStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    SECRETS_STEP_TYPE, classOf[MountSecretsFeatureStep])

  private val mountLocalFilesStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    MOUNT_LOCAL_FILES_STEP_TYPE, classOf[MountLocalFilesFeatureStep])

  private val builderUnderTest: KubernetesDriverBuilder =
    new KubernetesDriverBuilder(
      _ => basicFeatureStep,
      _ => credentialsStep,
      _ => serviceStep,
      _ => secretsStep,
      _ => mountLocalFilesStep)

  test("Apply fundamental steps all the time.") {
    val conf = KubernetesConf(
      new SparkConf(false),
      KubernetesDriverSpecificConf(
        None,
        "test-app",
        "main",
        Seq.empty),
      "prefix",
      "appId",
      Some("secret"),
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty)
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf),
      Map.empty,
      BASIC_STEP_TYPE,
      CREDENTIALS_STEP_TYPE,
      SERVICE_STEP_TYPE)
  }

  test("Apply secrets step if secrets are present.") {
    val conf = KubernetesConf(
      new SparkConf(false),
      KubernetesDriverSpecificConf(
        None,
        "test-app",
        "main",
        Seq.empty),
      "prefix",
      "appId",
      Some("secret"),
      Map.empty,
      Map.empty,
      Map("secret" -> "secretMountPath"),
      Map.empty)
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf),
      Map.empty,
      BASIC_STEP_TYPE,
      CREDENTIALS_STEP_TYPE,
      SERVICE_STEP_TYPE,
      SECRETS_STEP_TYPE)
  }

  test("Apply mounting small local files when present..") {
    val tempDir = Utils.createTempDir()
    val tempFile1 = new File(tempDir, "file1.txt")
    Files.write("a", tempFile1, Charsets.UTF_8)
    val tempFile2 = new File(tempDir, "file2.txt")
    Files.write("b", tempFile2, Charsets.UTF_8)
    val allFiles = Seq(
      tempFile1.getAbsolutePath,
      s"file://${tempFile2.getAbsolutePath}",
      "https://localhost:9000/file3.txt")
    val conf = KubernetesConf(
      new SparkConf(false).set("spark.files", allFiles.mkString(",")),
      KubernetesDriverSpecificConf(
        None,
        "test-app",
        "main",
        Seq.empty),
      "prefix",
      "appId",
      Some("secret"),
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty)
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf),
      Map("spark.files" -> allFiles.mkString(",")),
      BASIC_STEP_TYPE,
      CREDENTIALS_STEP_TYPE,
      SERVICE_STEP_TYPE,
      MOUNT_LOCAL_FILES_STEP_TYPE)
  }

  private def validateStepTypesApplied(
      resolvedSpec: KubernetesDriverSpec,
      additionalSystemProperties: Map[String, String],
      stepTypes: String*)
    : Unit = {
    assert(resolvedSpec.systemProperties.size === stepTypes.size + additionalSystemProperties.size)
    assert(additionalSystemProperties.toSet.subsetOf(resolvedSpec.systemProperties.toSet))
    stepTypes.foreach { stepType =>
      assert(resolvedSpec.pod.pod.getMetadata.getLabels.get(stepType) === stepType)
      assert(resolvedSpec.driverKubernetesResources.containsSlice(
        KubernetesFeaturesTestUtils.getSecretsForStepType(stepType)))
      assert(resolvedSpec.systemProperties(stepType) === stepType)
    }
  }
}
