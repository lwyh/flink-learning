/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.kubeclient.decorators;

import io.fabric8.kubernetes.api.model.*;
import org.apache.flink.kubernetes.kubeclient.FlinkPod;
import org.apache.flink.kubernetes.kubeclient.parameters.KubernetesTaskManagerParameters;
import org.apache.flink.kubernetes.kubeclient.resources.KubernetesToleration;
import org.apache.flink.kubernetes.utils.Constants;
import org.apache.flink.kubernetes.utils.KubernetesUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.apache.flink.kubernetes.utils.Constants.*;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * An initializer for the TaskManager {@link org.apache.flink.kubernetes.kubeclient.FlinkPod}.
 */
public class InitTaskManagerDecorator extends AbstractKubernetesStepDecorator {

	private final KubernetesTaskManagerParameters kubernetesTaskManagerParameters;

	public InitTaskManagerDecorator(KubernetesTaskManagerParameters kubernetesTaskManagerParameters) {
		this.kubernetesTaskManagerParameters = checkNotNull(kubernetesTaskManagerParameters);
	}

	@Override
	public FlinkPod decorateFlinkPod(FlinkPod flinkPod) {
		final Pod basicPod = new PodBuilder(flinkPod.getPod())
			.withApiVersion(Constants.API_VERSION)
			.editOrNewMetadata()
				.withName(kubernetesTaskManagerParameters.getPodName())
				.withLabels(kubernetesTaskManagerParameters.getLabels())
				.withAnnotations(kubernetesTaskManagerParameters.getAnnotations())
				.endMetadata()
			.editOrNewSpec()
				.withServiceAccountName(kubernetesTaskManagerParameters.getServiceAccount())
				.withRestartPolicy(Constants.RESTART_POLICY_OF_NEVER)
				.withHostNetwork(kubernetesTaskManagerParameters.isHostNetworkEnabled())
				.withDnsPolicy(
					kubernetesTaskManagerParameters.isHostNetworkEnabled()
						? DNS_PLOICY_HOSTNETWORK
						: DNS_PLOICY_DEFAULT)
				.withImagePullSecrets(kubernetesTaskManagerParameters.getImagePullSecrets())
				.withNodeSelector(kubernetesTaskManagerParameters.getNodeSelector())
				.withTolerations(kubernetesTaskManagerParameters.getTolerations().stream()
					.map(e -> KubernetesToleration.fromMap(e).getInternalResource())
					.collect(Collectors.toList()))
				.endSpec()
			.build();

		final Container basicMainContainer = decorateMainContainer(flinkPod.getMainContainer());

		return new FlinkPod.Builder(flinkPod)
			.withPod(basicPod)
			.withMainContainer(basicMainContainer)
			.build();
	}

	private Container decorateMainContainer(Container container) {
		//TM 资源
		final ResourceRequirements resourceRequirements = KubernetesUtils.getResourceRequirements(
				kubernetesTaskManagerParameters.getTaskManagerMemoryMB(),
				kubernetesTaskManagerParameters.getTaskManagerMemoryRequestFactor(),
				kubernetesTaskManagerParameters.getTaskManagerCPU(),
				kubernetesTaskManagerParameters.getTaskManagerCPURequestFactor(),
				kubernetesTaskManagerParameters.getTaskManagerExternalResources());

		return new ContainerBuilder(container)
				.withName(kubernetesTaskManagerParameters.getTaskManagerMainContainerName())
				.withImage(kubernetesTaskManagerParameters.getImage())
				.withImagePullPolicy(kubernetesTaskManagerParameters.getImagePullPolicy().name())
				.withResources(resourceRequirements)
				.withPorts(getContainerPorts())
				.withEnv(getCustomizedEnvs())
				.addNewEnv()
					.withName(ENV_FLINK_HOST_IP_ADDRESS)
					.withValueFrom(new EnvVarSourceBuilder()
					.withNewFieldRef(API_VERSION, HOST_IP_FIELD_PATH)
					.build())
					.endEnv()
				.addNewEnv()
					.withName(ENV_FLINK_POD_IP_ADDRESS)
					.withValueFrom(new EnvVarSourceBuilder()
					.withNewFieldRef(API_VERSION, POD_IP_FIELD_PATH)
					.build())
					.endEnv()
				.addNewEnv()
					.withName("CLUSTER_ID")
					.withValue(kubernetesTaskManagerParameters.getClusterId())
					.endEnv()
				.build();
	}

	private List<ContainerPort> getContainerPorts() {
		if (kubernetesTaskManagerParameters.isHostNetworkEnabled()) {
			return Collections.emptyList();
		}
		return Collections.singletonList(
			new ContainerPortBuilder()
				.withName(Constants.TASK_MANAGER_RPC_PORT_NAME)
				.withContainerPort(kubernetesTaskManagerParameters.getRPCPort())
				.build());
	}

	private List<EnvVar> getCustomizedEnvs() {
		return kubernetesTaskManagerParameters.getEnvironments()
			.entrySet()
			.stream()
			.map(kv -> new EnvVar(kv.getKey(), kv.getValue(), null))
			.collect(Collectors.toList());
	}
}
