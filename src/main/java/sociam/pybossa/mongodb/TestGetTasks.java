package sociam.pybossa.mongodb;


import org.json.JSONObject;

import sociam.pybossa.TaskPerformer;

public class TestGetTasks {

	public static void main(String[] args) {
		JSONObject tasks = TaskPerformer.getTasks(0);
		System.out.println(tasks.toString());
	}

}
