package net.boomerangplatform.kube.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.apis.BatchV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.auth.ApiKeyAuth;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.models.V1ConfigMap;
import io.kubernetes.client.models.V1ConfigMapList;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1EnvVar;
import io.kubernetes.client.models.V1Job;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1PersistentVolumeClaim;
import io.kubernetes.client.models.V1PersistentVolumeClaimList;
import io.kubernetes.client.models.V1PersistentVolumeClaimSpec;
import io.kubernetes.client.models.V1PersistentVolumeClaimStatus;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1ResourceRequirements;
import io.kubernetes.client.models.V1Status;
import io.kubernetes.client.util.Watch;

public abstract class AbstractKubeServiceImpl implements AbstractKubeService {
	
	@Value("${kube.api.base.path}")
	protected String kubeApiBasePath;

	@Value("${kube.api.token}")
	protected String kubeApiToken;
	
	@Value("${kube.api.debug}")
	protected String kubeApiDebug;
	
	@Value("${kube.api.type}")
	protected String kubeApiType;

	@Value("${kube.namespace}")
	protected String kubeNamespace;
	
	@Value("${kube.image.pullPolicy}")
	protected String kubeImagePullPolicy;
	
	@Value("${kube.worker.pvc.initialSize}")
	protected String kubeWorkerPVCInitialSize;
	
	@Value("${kube.worker.job.backOffLimit}")
	protected Integer kubeWorkerJobBackOffLimit;
	
	@Value("${kube.worker.debug}")
	protected Boolean kubeWorkerDebug;
	
	@Value("${proxy.enable}")
	protected Boolean proxyEnabled;
	
	@Value("${proxy.host}")
	protected String proxyHost;
	
	@Value("${proxy.port}")
	protected String proxyPort;
	
	@Value("${proxy.ignore}")
	protected String proxyIgnore;
	
	@Value("${controller.service.host}")
	protected String bmrgControllerServiceURL;
	
	final static String PREFIX = "bmrg-flow-";
	
	final static String PREFIX_CFGMAP = PREFIX + "cfg-";
	
	final static String PREFIX_PVC = PREFIX + "pvc-";
	
	final static String PREFIX_VOL = PREFIX + "vol-";
	
	final static String API_PRETTY = "true";
	
	final static Boolean API_INCLUDEUNINITIALIZED = false;
	
	@Override
	public abstract V1Job createJob(String workflowName, String workflowId, String workflowActivityId,String taskName, String taskId, List<String> arguments, Map<String, String> taskInputProperties);
	
	@Override
	public V1Job watchJob(String workflowId, String workflowActivityId, String taskId) throws Exception {		
		BatchV1Api api = new BatchV1Api();

		String labelSelector = "org=bmrg,app=bmrg-flow,workflow-id="+workflowId+",workflow-activity-id="+workflowActivityId+",task-id="+taskId;

		Watch<V1Job> watch = Watch.createWatch(
				createWatcherApiClient(), api.listNamespacedJobCall(kubeNamespace, API_INCLUDEUNINITIALIZED, API_PRETTY, null, null, labelSelector, null, null, null, true, null, null),
				new TypeToken<Watch.Response<V1Job>>() {
				}.getType());
		
		V1Job jobResult = new V1Job();
		try {
			for (Watch.Response<V1Job> item : watch) {
				System.out.println(item.type + " : " + item.object.getMetadata().getName());
				System.out.println(item.object.getStatus());
				if (item.object.getStatus().getConditions() != null && !item.object.getStatus().getConditions().isEmpty()) {
					if (item.object.getStatus().getConditions().get(0).getType().equals("Complete")) {
						jobResult = item.object;
						break;
					} else if (item.object.getStatus().getConditions().get(0).getType().equals("Failed")) {
						throw new Exception("Task (" + taskId + ") has failed to execute " + kubeWorkerJobBackOffLimit + " times triggering failure.");
					}
				}
			}
		} finally {
			watch.close();
		}
//		getJobPod(workflowId, workflowActivityId);
		return jobResult;
	}
	
	//Commenting out to determine if it is needed. This was in there as a delay.
//	private void getJobPod(String workflowId, String workflowActivityId) {		
//		CoreV1Api api = new CoreV1Api();
//		String labelSelector = "org=bmrg,app=bmrg-flow,workflow-id="+workflowId+",workflow-activity-id="+workflowActivityId;
//		
//		try {
//			V1PodList podList = api.listNamespacedPod(kubeNamespace, API_INCLUDEUNINITIALIZED, API_PRETTY, null, null, labelSelector, null, null, 60, false);
//			podList.getItems().forEach(pod -> {
//				System.out.println(pod.toString());
//				System.out.println(" pod Name: " + pod.getMetadata().getName());
//			});
//		} catch (ApiException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//	}
	
	@Override
	public String getPodLog(String workflowId, String workflowActivityId, String taskId) throws ApiException, IOException {		
		CoreV1Api api = new CoreV1Api();
		String labelSelector = "org=bmrg,app=bmrg-flow,workflow-id="+workflowId+",workflow-activity-id="+workflowActivityId+",task-id=" + taskId;

	    PodLogs logs = new PodLogs();
	    List<V1Pod> listOfPods = 
	    		api
	            .listNamespacedPod(kubeNamespace, API_INCLUDEUNINITIALIZED, API_PRETTY, null, null, labelSelector, null, null, 60, false)
	            .getItems();
	    V1Pod pod = new V1Pod();
	    ByteArrayOutputStream baos = new ByteArrayOutputStream(); 
	    
	    if (!listOfPods.isEmpty()) {
	    	pod = listOfPods.get(0);
	    	InputStream is = logs.streamNamespacedPodLog(pod);
		    //ByteStreams.copy(is, System.out);
		    ByteStreams.copy(is, baos);
	    }
		return baos.toString();
	}
	
	@Override
	public StreamingResponseBody streamPodLog(HttpServletResponse response, String workflowId, String workflowActivityId, String taskId) throws ApiException, IOException {		
		CoreV1Api api = new CoreV1Api();
		String labelSelector = "org=bmrg,app=bmrg-flow,workflow-id="+workflowId+",workflow-activity-id="+workflowActivityId+",task-id=" + taskId;

	    PodLogs logs = new PodLogs();
	    V1Pod pod = 
	    		api
	            .listNamespacedPod(kubeNamespace, API_INCLUDEUNINITIALIZED, API_PRETTY, null, null, labelSelector, null, null, 60, false)
	            .getItems()
	            .get(0);

	    InputStream is = logs.streamNamespacedPodLog(pod);
	    ByteArrayOutputStream baos = new ByteArrayOutputStream(); 
	    //ByteStreams.copy(is, System.out);
	    ByteStreams.copy(is, baos);
		
		return outputStream -> {
		    int nRead;
		    byte[] data = new byte[1024];
		    while ((nRead = is.read(data, 0, data.length)) != -1) {
		        System.out.println("Writing some bytes of file...");
		        outputStream.write(data, 0, nRead);
		    }
		};
	}
	
	public V1PersistentVolumeClaim createPVC(String workflowName, String workflowId, String workflowActivityId, String pvcSize) throws ApiException {
		// Setup	
		CoreV1Api api = new CoreV1Api();
		V1PersistentVolumeClaim body = new V1PersistentVolumeClaim(); // V1PersistentVolumeClaim |

		// Create Metadata
		V1ObjectMeta metadata = new V1ObjectMeta();
		metadata.annotations(createAnnotations(workflowName, workflowId, workflowActivityId, null));
		metadata.labels(createLabels(workflowName, workflowId, workflowActivityId, null));
		metadata.generateName(PREFIX_PVC);
		body.metadata(metadata);

		// Create PVC Spec
		V1PersistentVolumeClaimSpec pvcSpec = new V1PersistentVolumeClaimSpec();
		List<String> pvcAccessModes = new ArrayList<String>();
		pvcAccessModes.add("ReadWriteMany");
		pvcSpec.accessModes(pvcAccessModes);
		V1ResourceRequirements pvcResourceReq = new V1ResourceRequirements();
		Map<String, Quantity> pvcRequests = new HashMap<String, Quantity>();
		if (pvcSize == null || pvcSize.isEmpty()) {
			pvcSize = kubeWorkerPVCInitialSize;
		}
		pvcRequests.put("storage", Quantity.fromString(pvcSize));
		pvcResourceReq.requests(pvcRequests);
		pvcSpec.resources(pvcResourceReq);
		body.spec(pvcSpec);
		
		V1PersistentVolumeClaim result = new V1PersistentVolumeClaim();
	    result = api.createNamespacedPersistentVolumeClaim(kubeNamespace, body, API_INCLUDEUNINITIALIZED, API_PRETTY, null);
	    System.out.println(result);
	    return result;
	}
	
	@Override
	public V1PersistentVolumeClaimStatus watchPVC(String workflowId, String workflowActivityId) throws ApiException, IOException {
		CoreV1Api api = new CoreV1Api();
		String labelSelector = "org=bmrg,app=bmrg-flow,workflow-id="+workflowId+",workflow-activity-id="+workflowActivityId;
		
		Watch<V1PersistentVolumeClaim> watch = Watch.createWatch(
				createWatcherApiClient(), api.listNamespacedPersistentVolumeClaimCall(kubeNamespace, API_INCLUDEUNINITIALIZED, API_PRETTY, null, null, labelSelector, null, null, null, true, null, null),
				new TypeToken<Watch.Response<V1PersistentVolumeClaim>>() {
				}.getType());
		V1PersistentVolumeClaimStatus result = null;
		try {
			for (Watch.Response<V1PersistentVolumeClaim> item : watch) {
				System.out.printf("%s : %s%n", item.type, item.object.getMetadata().getName());
				System.out.println(item.object.getStatus());
				result = item.object.getStatus();
				if (result != null && result.getPhase() != null && result.getPhase().equals("Bound")) {
					break;
				}
			}
		} finally {
			watch.close();
		}
		return result;
	}
	
	@Override
	public V1Status deletePVC(String workflowId, String workflowActivityId) {
		CoreV1Api api = new CoreV1Api();
		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		V1Status result = new V1Status();
		
		try {
			result = api.deleteNamespacedPersistentVolumeClaim(getPVCName(workflowId, workflowActivityId), kubeNamespace, deleteOptions, API_PRETTY, null, null, null, null);
		} catch (JsonSyntaxException e) {
            if (e.getCause() instanceof IllegalStateException) {
                IllegalStateException ise = (IllegalStateException) e.getCause();
                if (ise.getMessage() != null && ise.getMessage().contains("Expected a string but was BEGIN_OBJECT")) {
                	System.out.println("Catching exception because of issue https://github.com/kubernetes-client/java/issues/86");
            	} else {
	                System.err.println("Exception when running deletePV()");
	    		    e.printStackTrace();
            	}
            }
        } catch (ApiException e) {
		    System.err.println("Exception when running deletePVC()");
		    e.printStackTrace();
		}
		return result;
	}
	
	protected String getPVCName(String workflowId, String workflowActivityId) {
		CoreV1Api api = new CoreV1Api();
		String labelSelector = "org=bmrg,app=bmrg-flow,workflow-id="+workflowId+",workflow-activity-id="+workflowActivityId;
		
		try {
			V1PersistentVolumeClaimList persistentVolumeClaimList = api.listNamespacedPersistentVolumeClaim(kubeNamespace, API_INCLUDEUNINITIALIZED, API_PRETTY, null, null, labelSelector, null, null, 60, false);
			persistentVolumeClaimList.getItems().forEach(pvc -> {
				System.out.println(pvc.toString());
				System.out.println(" PVC Name: " + pvc.getMetadata().getName());
			});
			if (!persistentVolumeClaimList.getItems().isEmpty() && persistentVolumeClaimList.getItems().get(0).getMetadata().getName() != null) {
				System.out.println("----- End getPVCName() -----");
				return persistentVolumeClaimList.getItems().get(0).getMetadata().getName();
			}
		} catch (ApiException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return "";
	}

	@Override
	public V1ConfigMap createWorkflowConfigMap(String workflowName, String workflowId, String workflowActivityId, Map<String, String> inputProps) throws ApiException, IOException {
		V1ConfigMap body = new V1ConfigMap(); // V1PersistentVolumeClaim |
		
		// Create Metadata
		V1ObjectMeta metadata = new V1ObjectMeta();
		metadata.annotations(createAnnotations(workflowName, workflowId, workflowActivityId, null));
		metadata.labels(createLabels(workflowName, workflowId, workflowActivityId, null));
		//metadata.generateName(PREFIX_CFGMAP);
		metadata.name(PREFIX_CFGMAP + workflowActivityId); //We are using a fixed name to make it easier to find for subsequent calls as not all the configmap API's search on labels. Some take name as the parameter.
		body.metadata(metadata);
		
		//Create Data
		Map<String, String> inputsWithFixedKeys = new HashMap<String, String>();
		Map<String, String> sysProps = new HashMap<String, String>();
		sysProps.put("activity.id", workflowActivityId);
		sysProps.put("workflow.name", workflowName);
		sysProps.put("workflow.id", workflowId);
		sysProps.put("worker.debug", kubeWorkerDebug.toString());
		sysProps.put("controller.service.url", bmrgControllerServiceURL);
		inputsWithFixedKeys.put("workflow.input.properties", createConfigMapProp(inputProps));
		inputsWithFixedKeys.put("workflow.system.properties", createConfigMapProp(sysProps));
		body.data(inputsWithFixedKeys);
		
		//Create ConfigMap
		V1ConfigMap result = createConfigMap(body);
		return result;
	}
	
	@Override
	public V1ConfigMap createTaskConfigMap(String workflowName, String workflowId, String workflowActivityId, String taskName, String taskId, Map<String, String> inputProps) throws ApiException, IOException {
		V1ConfigMap body = new V1ConfigMap(); // V1PersistentVolumeClaim |
		
		// Create Metadata
		V1ObjectMeta metadata = new V1ObjectMeta();
		metadata.annotations(createAnnotations(workflowName, workflowId, workflowActivityId, taskId));
		metadata.labels(createLabels(workflowName, workflowId, workflowActivityId, taskId));
		//metadata.generateName(PREFIX_CFGMAP);
		metadata.name(PREFIX_CFGMAP + workflowActivityId + "-" + taskId); //We are using a fixed name to make it easier to find for subsequent calls as not all the configmap API's search on labels. Some take name as the parameter.
		body.metadata(metadata);
		
		//Create Data
		Map<String, String> inputsWithFixedKeys = new HashMap<String, String>();
		Map<String, String> sysProps = new HashMap<String, String>();
		sysProps.put("task.id", taskId);
		sysProps.put("task.name", taskName);
		inputsWithFixedKeys.put("task.input.properties", createConfigMapProp(inputProps));
		inputsWithFixedKeys.put("task.system.properties", createConfigMapProp(sysProps));
		body.data(inputsWithFixedKeys);
		
		//Create ConfigMap
		V1ConfigMap result = createConfigMap(body);
		return result;
	}
	
	protected V1ConfigMap createConfigMap(V1ConfigMap body) throws ApiException, IOException {
		CoreV1Api api = new CoreV1Api();
		
		//Create ConfigMap
		V1ConfigMap result = new V1ConfigMap();
		try {
		    result = api.createNamespacedConfigMap(kubeNamespace, body, API_INCLUDEUNINITIALIZED, API_PRETTY, null);
		    System.out.println(result);
		} catch (ApiException e) {
		    System.err.println("Exception when calling CoreV1Api#createNamespacedConfigMap");
		    e.printStackTrace();
		    throw e;
		}
		return result;
	}
	
	@Override
	public V1ConfigMap watchConfigMap(String workflowId, String workflowActivityId, String taskId) throws ApiException, IOException {
		CoreV1Api api = new CoreV1Api();
		String labelSelector = "org=bmrg,app=bmrg-flow,workflow-id="+workflowId+",workflow-activity-id="+workflowActivityId;
		if (taskId != null) {
			labelSelector = labelSelector.concat(",task-id=" + taskId);
		}
		System.out.println("  task-id: " + taskId);
		System.out.println("  labelSelector: " + labelSelector);
		
		Watch<V1ConfigMap> watch = Watch.createWatch(
				createWatcherApiClient(), api.listNamespacedConfigMapCall(kubeNamespace, API_INCLUDEUNINITIALIZED, API_PRETTY, null, null, labelSelector, null, null, null, true, null, null),
				new TypeToken<Watch.Response<V1ConfigMap>>() {
				}.getType());
		V1ConfigMap result = null;
		try {
			for (Watch.Response<V1ConfigMap> item : watch) {
				System.out.printf("%s : %s%n", item.type, item.object.getMetadata().getName());
				result = item.object;
				break;
			}
		} finally {
			watch.close();
		}
		return result;
	}
	
	protected V1ConfigMap getConfigMap(String workflowId, String workflowActivityId, String taskId) {
		V1ConfigMap configMap = null;
		
		CoreV1Api api = new CoreV1Api();
		String labelSelector = "org=bmrg,app=bmrg-flow,workflow-id="+workflowId+",workflow-activity-id="+workflowActivityId;
		if (taskId != null) {
			labelSelector = labelSelector.concat(",task-id=" + taskId);
		}
		System.out.println("  task-id: " + taskId);
		System.out.println("  labelSelector: " + labelSelector);
		try {
			V1ConfigMapList configMapList = api.listNamespacedConfigMap(kubeNamespace, API_INCLUDEUNINITIALIZED, API_PRETTY, null, null, labelSelector, null, null, 60, false);
			configMapList.getItems().forEach(cfgmap -> {
				System.out.println(cfgmap.toString());
			});
			if (!configMapList.getItems().isEmpty()) {
				configMap = configMapList.getItems().get(0);
			}
		} catch (ApiException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return configMap;
	}
	
	protected String getConfigMapName(V1ConfigMap configMap) {
		String configMapName = "";
		
		if (configMap != null && !configMap.getMetadata().getName().isEmpty()) {
			configMapName = configMap.getMetadata().getName();
			System.out.println(" ConfigMap Name: " + configMapName);
		}
		return configMapName;
	}
	
	private String getConfigMapDataProp(V1ConfigMap configMap, String key) {
		String configMapDataProp = "";
		
		if (configMap.getData().get(key) != null) {
			configMapDataProp = configMap.getData().get(key);
			System.out.println(" ConfigMap Input Properties Data: " + configMapDataProp);
		}
		return configMapDataProp;
	}
	
	private void patchConfigMap(String name, String dataKey, String origData, String newData) {
		CoreV1Api api = new CoreV1Api();

		JsonObject jsonPatchObj = new JsonObject();
		jsonPatchObj.addProperty("op", "add");
		jsonPatchObj.addProperty("path", "/data/" + dataKey);
		jsonPatchObj.addProperty("value", origData.endsWith("\n") ? origData + newData : origData + "\n" + newData);
		
		ArrayList<JsonObject> arr = new ArrayList<>();
	    arr.add(((JsonElement) (new Gson()).fromJson(jsonPatchObj.toString(), JsonElement.class)).getAsJsonObject());
		try {
		    V1ConfigMap result = api.patchNamespacedConfigMap(name, kubeNamespace, arr, API_PRETTY, null);
		    System.out.println(result);
		} catch (ApiException e) {
		    System.err.println("Exception when calling CoreV1Api#patchNamespacedConfigMap");
		    e.printStackTrace();
		}
	}
	
	@Override
	public void patchTaskConfigMap(String workflowId, String workflowActivityId, String taskId, String taskName, Map<String, String> properties) {
		V1ConfigMap wfConfigMap = getConfigMap(workflowId, workflowActivityId, null);
		String fileName = taskName + ".output.properties";
		patchConfigMap(getConfigMapName(wfConfigMap), fileName, getConfigMapDataProp(wfConfigMap, fileName), createConfigMapProp(properties));
	}
	
	@Override
	public Map<String, String> getTaskOutPutConfigMapData(String workflowId, String workflowActivityId, String taskId, String taskName) {
		System.out.println("  taskName: " + taskName);
		Map<String, String> properties = new HashMap<String, String>();
		V1ConfigMap wfConfigMap = getConfigMap(workflowId, workflowActivityId, null);
		String fileName = taskName.replace(" ", "") + ".output.properties";
		String dataString = getConfigMapDataProp(wfConfigMap, fileName);
		
		properties = Pattern.compile("\\s*\\n\\s*")
			    .splitAsStream(dataString.trim())
			    .map(s -> s.split("=", 2))
			    .collect(Collectors.toMap(a -> a[0], a -> a.length>1? a[1]: ""));
		
		System.out.println("  properties: " + properties.toString());
		return properties;
	}
	
	@Override
	public V1Status deleteConfigMap(String workflowId, String workflowActivityId, String taskId) {
		CoreV1Api api = new CoreV1Api();
		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		V1Status result = new V1Status();
		
		String name = PREFIX_CFGMAP + workflowActivityId;
		if (taskId != null) {
			name = name.concat("-" + taskId);
		}
		System.out.println("  Configmap to delete: " + name);
		
		try {
			result = api.deleteNamespacedConfigMap(name, kubeNamespace, deleteOptions, API_PRETTY, null, null, null, null);
		} catch (JsonSyntaxException e) {
            if (e.getCause() instanceof IllegalStateException) {
                IllegalStateException ise = (IllegalStateException) e.getCause();
                if (ise.getMessage() != null && ise.getMessage().contains("Expected a string but was BEGIN_OBJECT")) {
                	System.out.println("Catching exception because of issue https://github.com/kubernetes-client/java/issues/86");
            	} else {
	                System.err.println("Exception when running deleteConfigMap()");
	    		    e.printStackTrace();
            	}
            }
        } catch (ApiException e) {
		    System.err.println("Exception when running deleteConfigMap()");
		    e.printStackTrace();
		}
		return result;
	}
	
	protected ApiClient createWatcherApiClient() {
//		https://github.com/kubernetes-client/java/blob/master/util/src/main/java/io/kubernetes/client/util/Config.java#L57
//		ApiClient watcherClient = Config.fromToken(kubeApiBasePath, kubeApiToken, false);
		
		ApiClient watcherClient = io.kubernetes.client.Configuration.getDefaultApiClient().setVerifyingSsl(false).setDebugging(false);
		
		if (!kubeApiToken.isEmpty()) {
			ApiKeyAuth watcherApiKeyAuth = (ApiKeyAuth) watcherClient.getAuthentication("BearerToken");
			watcherApiKeyAuth.setApiKey(kubeApiToken);
			watcherApiKeyAuth.setApiKeyPrefix("Bearer");
		}
		watcherClient.getHttpClient().setReadTimeout(300, TimeUnit.SECONDS);
		return watcherClient;
	}
	
	protected List<V1EnvVar> createProxyEnvVars() {
		List<V1EnvVar> proxyEnvVars = new ArrayList<V1EnvVar>();
		
		proxyEnvVars.add(createEnvVar("HTTP_PROXY","http://" + proxyHost + ":" + proxyPort));
		proxyEnvVars.add(createEnvVar("HTTPS_PROXY","https://" + proxyHost + ":" + proxyPort));
		proxyEnvVars.add(createEnvVar("http_proxy","http://" + proxyHost + ":" + proxyPort));
		proxyEnvVars.add(createEnvVar("https_proxy","https://" + proxyHost + ":" + proxyPort));
		proxyEnvVars.add(createEnvVar("NO_PROXY",proxyIgnore));
		proxyEnvVars.add(createEnvVar("no_proxy",proxyIgnore));
		proxyEnvVars.add(createEnvVar("use_proxy","on"));

		return proxyEnvVars;
	}
	
	protected V1EnvVar createEnvVar(String key, String value) {
		V1EnvVar envVar = new V1EnvVar();
		envVar.setName(key);
		envVar.setValue(value);
		return envVar;
	}
	
	protected Map<String, String> createAnnotations(String workflowName, String workflowId, String workflowActivityId, String taskId) {
		Map<String, String> annotations = new HashMap<String, String>();
		annotations.put("boomerangplatform.net/org", "bmrg");
		annotations.put("boomerangplatform.net/app", "bmrg-flow");
		annotations.put("boomerangplatform.net/workflow-name", workflowName);
		annotations.put("boomerangplatform.net/workflow-id", workflowId);
		annotations.put("boomerangplatform.net/workflow-activity-id", workflowActivityId);
		Optional.ofNullable(taskId).ifPresent(str -> annotations.put("boomerangplatform.net/task-id", str));
		
		return annotations;
	}
	
	protected Map<String, String> createLabels(String workflowName, String workflowId, String workflowActivityId, String taskId) {
		Map<String, String> labels = new HashMap<String, String>();
		labels.put("org", "bmrg");
		labels.put("app", "bmrg-flow");
		Optional.ofNullable(workflowName).ifPresent(str -> labels.put("workflow-name", str.replace(" ", "")));
		Optional.ofNullable(workflowId).ifPresent(str -> labels.put("workflow-id", str));
		Optional.ofNullable(workflowActivityId).ifPresent(str -> labels.put("workflow-activity-id", str));
		Optional.ofNullable(taskId).ifPresent(str -> labels.put("task-id", str));
		return labels;
	}
	
	protected String createConfigMapProp(Map<String, String> properties) {
		StringBuilder propsString = new StringBuilder();
		
		//TODO fix up null check and handling
		if (properties != null && !properties.isEmpty()) {
			properties.forEach((key, value) -> {
				//propsString.append(key.replace("-", "_").replace(".", "_").toUpperCase());
				propsString.append(key);
				propsString.append("=");
				propsString.append(value);
				propsString.append("\n");
			});
		} else {
			propsString.append("\n");
		}
		
		return propsString.toString();
	}
	
	protected String createConfigMapPropWithPrefix(Map<String, String> properties, String prefix) {
		StringBuilder propsString = new StringBuilder();
		
		properties.forEach((key, value) -> {
			propsString.append(prefix + key.replace("-", "_").replace(".", "_").toUpperCase());
			propsString.append("=");
			propsString.append(value);
			propsString.append("\n");
		});
		
		return propsString.toString();
	}
}