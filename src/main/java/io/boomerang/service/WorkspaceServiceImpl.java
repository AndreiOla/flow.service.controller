package io.boomerang.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.kube.exception.KubeRuntimeException;
import io.boomerang.kube.service.KubeServiceImpl;
import io.boomerang.model.Response;
import io.boomerang.model.Workspace;
import io.fabric8.kubernetes.client.KubernetesClientException;

@Service
public class WorkspaceServiceImpl implements WorkspaceService {

  private static final Logger LOGGER = LogManager.getLogger(WorkspaceServiceImpl.class);
  
  @Value("${kube.workspace.storage.size}")
  protected String storageSize;
  
  @Value("${kube.workspace.storage.class}")
  protected String storageClassName;
  
  @Value("${kube.workspace.storage.accessMode}")
  protected String storageAccessMode;
  
  @Value("${kube.timeout.waitUntil}")
  protected long waitUntilTimeout;

//    @Autowired
//    private KubeServiceImpl kubeService;
    
    @Autowired
    private KubeServiceImpl kubeService;
	
    @Override
    public Response createWorkspace(Workspace workspace) {
      Response response =
          new Response("0", "Workspace (" + workspace.getId() + ") PVC has been created successfully.");
      try {
        LOGGER.info("Workspace: " + workspace.toString());
        boolean pvcExists = kubeService.checkWorkspacePVCExists(workspace.getId(), false);
        if (!pvcExists) {
          String size = workspace.getSize() == null || workspace.getSize().isEmpty() ? storageSize : workspace.getSize();
          String className = workspace.getClassName();
          String accessMode = workspace.getAccessMode() == null || workspace.getAccessMode().isEmpty() ? storageAccessMode : workspace.getAccessMode();
          kubeService.createWorkspacePVC(workspace.getName(), workspace.getId(), workspace.getLabels(), size, className, accessMode, waitUntilTimeout);
        } else if (pvcExists) {
          response = new Response("0", "Workspace (" + workspace.getId() + ") PVC already existed.");
        }
      } catch (KubeRuntimeException | KubernetesClientException | InterruptedException e) {
        LOGGER.error(e.getMessage());
        throw new BoomerangException(e, 1, e.toString(), HttpStatus.INTERNAL_SERVER_ERROR);
      } catch (IllegalArgumentException e) {
        if (e.getMessage().contains("condition not found")) {
          throw new BoomerangException(e, BoomerangError.PVC_CREATE_CONDITION_NOT_MET, "" + waitUntilTimeout);
        } else {
          throw new BoomerangException(e, 1, e.toString(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
      }
      LOGGER.info("createWorkspace() - " + response.getMessage());
      return response;
    }

    @Override
    public Response deleteWorkspace(Workspace workspace) {
      Response response =
          new Response("0", "Workspace (" + workspace.getId() + ") has been successfully deleted.");
      try {
        LOGGER.info(workspace.toString());
        kubeService.deleteWorkspacePVC(workspace.getId());
      } catch (KubeRuntimeException e) {
        throw new BoomerangException(e, 1, e.toString(), HttpStatus.INTERNAL_SERVER_ERROR);
      }
      LOGGER.info("deleteWorkspace() - " + response.getMessage());
      return response;
    }
}
