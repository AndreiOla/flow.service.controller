package io.boomerang.service.mock;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import io.boomerang.model.Task;
import io.boomerang.model.TaskResponse;
import io.boomerang.service.TaskService;

@Service
@Profile("mock")
public class MockControllerService implements TaskService {

	@Override
	public TaskResponse executeTask(Task task) {
		return null;
	}

//	@Override
//	public Response createWorkflow(Workflow workflow) {
//		return null;
//	}
//
//	@Override
//	public Response terminateWorkflow(Workflow workflow) {
//		return null;
//	}

//  @Override
//  public Response createWorkspace(Workspace workspace) {
//    // TODO Auto-generated method stub
//    return null;
//  }
//
//  @Override
//  public Response deleteWorkspace(Workspace workspace) {
//    // TODO Auto-generated method stub
//    return null;
//  }

  @Override
  public TaskResponse terminateTask(Task task) {
    // TODO Auto-generated method stub
    return null;
  }

}
