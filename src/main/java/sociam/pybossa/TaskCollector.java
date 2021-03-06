package sociam.pybossa;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.bson.Document;
import org.json.JSONObject;

import sociam.pybossa.config.Config;
import sociam.pybossa.methods.MongodbMethods;
import sociam.pybossa.methods.PybossaMethods;
import sociam.pybossa.methods.TwitterMethods;
import sociam.pybossa.util.TwitterAccount;
import twitter4j.Twitter;

/**
 * 
 * @author user Saud Aljaloud
 * @author email sza1g10@ecs.soton.ac.uk
 *
 */
public class TaskCollector {

	final static Logger logger = Logger.getLogger(TaskCollector.class);
	final static SimpleDateFormat MongoDBformatter = new SimpleDateFormat(
			"yyyy-MM-dd HH:mm:ss");
	final static SimpleDateFormat PyBossaformatter = new SimpleDateFormat(
			"yyyy-mm-dd'T'hh:mm:ss.SSSSSS");
	final static String SOURCE = "Twitter";

	// caching tasksIDs
	static HashMap<Integer, Integer> cachedTaskIDsAndProjectsIDs = new HashMap<>();

	static Twitter twitter;

	public static void main(String[] args) {

		PropertyConfigurator.configure("log4j.properties");
		twitter = TwitterAccount.setTwitterAccount(2);
		logger.info("TaskCollector will be repeated every "
				+ Config.TaskCollectorTrigger + " ms");
		try {
			while (true) {
				Config.reload();
				run();
				logger.info("Sleeping for " + Config.TaskCollectorTrigger
						+ " ms");
				Thread.sleep(Integer.valueOf(Config.TaskCollectorTrigger));
			}
		} catch (InterruptedException e) {
			logger.error("Error ", e);
		}

	}

	public static void run() {
		try {

			logger.debug("Getting time line from Twitter");
			ArrayList<JSONObject> ResponsesFromTwitter = TwitterMethods
					.getTimeLineAsJsons(twitter);
			if (ResponsesFromTwitter != null) {
				logger.debug("There are " + ResponsesFromTwitter.size()
						+ " tweets to be processed");
				for (JSONObject jsonObject : ResponsesFromTwitter) {

					// logger.debug("Processing a new twitter object ");
					if (!jsonObject.isNull("in_reply_to_status_id_str")) {
						logger.debug("Found a reply tweet " + jsonObject);
						String in_reply_to_status_id_str = jsonObject
								.getString("in_reply_to_status_id_str");
						String reply = jsonObject.getString("text");
						String in_reply_to_screen_name = jsonObject
								.getString("in_reply_to_screen_name");
						String taskResponse = reply.replaceAll("@"
								+ in_reply_to_screen_name, "");

						// // store the reply id
						// String id_str = jsonObject.getString("id_str");

						// store the use screen name
						JSONObject userJson = jsonObject.getJSONObject("user");

						// store the replier user name
						String screen_name = userJson.getString("screen_name");

						logger.debug("Checking if the reply has already being stored");
						// Document taskRun = getTaskRunsFromMongoDB(id_str);
						// if (taskRun == null) {

						logger.debug("Looking for the original tweet for the reply");
						JSONObject orgTweet = TwitterMethods.getTweetByID(
								String.valueOf(in_reply_to_status_id_str),
								twitter);
						// loop through tweets till you find the orginal
						// tweet
						if (orgTweet != null) {
							while (!orgTweet
									.isNull("in_reply_to_status_id_str")) {
								orgTweet = TwitterMethods
										.getTweetByID(
												orgTweet.getString("in_reply_to_status_id_str"),
												twitter);
							}
							logger.debug("Original tweet was found");

							String orgTweetText = orgTweet.getString("text");
							Pattern pattern = Pattern.compile("(#t[0-9]+)");
							Matcher matcher = pattern.matcher(orgTweetText);
							String taskID = "";
							if (matcher.find()) {
								logger.debug("Found a taskID in the orginal tweet");
								taskID = matcher.group(1).replaceAll("#t", "");
								Integer intTaskID = Integer.valueOf(taskID);

								// cache taskIDs
								if (!cachedTaskIDsAndProjectsIDs
										.containsKey(intTaskID)) {
									logger.debug("TaskID is not in the cache");
									logger.debug("Retriving Task id from Collection: "
											+ Config.taskCollection);
									Document doc = MongodbMethods
											.getTaskFromMongoDB(intTaskID);
									if (doc != null) {
										int project_id = doc
												.getInteger("project_id");
										cachedTaskIDsAndProjectsIDs.put(
												intTaskID, project_id);
										if (insertTaskRun(taskResponse,
												intTaskID, project_id,
												screen_name, SOURCE)) {
											logger.debug("Task run was completely processed");
										} else {
											logger.error("Failed to process the task run");
										}
									} else {
										logger.error("Couldn't find task with ID "
												+ taskID);
										// TODO: Remove tweets that do not have
										// records in MongoDB
									}
								} else {
									logger.debug("Task ID was found in the cache");
									insertTaskRun(taskResponse, intTaskID,
											cachedTaskIDsAndProjectsIDs
													.get(intTaskID),
											screen_name, SOURCE);
								}

							} else {
								logger.error("reply: \\"
										+ reply
										+ " was not being identified with an associated task in the original text: \\"
										+ orgTweetText);
							}

						}
					}
					// else {
					// logger.debug("This is not a reply tweet");
					//
					// }

				}
			} else {
				logger.info("Time line was null");
			}

		} catch (Exception e) {
			logger.error("Error ", e);
		}
	}

	public static Boolean insertTaskRun(String text, int task_id,
			int project_id, String contributor_name, String source) {

		Document taskRun = MongodbMethods.getTaskRunsFromMongoDB(task_id,
				contributor_name, text);
		if (taskRun != null) {
			logger.error("You are only allowed one contribution for each task.");
			logger.error("task_id= " + task_id + " screen_name: "
					+ contributor_name);
			return false;
		}

		JSONObject jsonData = PybossaMethods.BuildJsonTaskRunContent(text,
				task_id, project_id);
		if (MongodbMethods.insertTaskRunIntoMongoDB(jsonData, contributor_name,
				source)) {
			logger.debug("Task run was successfully inserted into MongoDB");
			// Project has to be reqested before inserting a task run
			logger.debug("Requesting the project ID from PyBossa before inserting it");
			String postURL = Config.PyBossahost + Config.taskRunDir
					+ Config.api_key;
			JSONObject postResponse = PybossaMethods.insertTaskRunIntoPyBossa(
					postURL, jsonData);
			if (postResponse != null) {
				logger.debug("Task run was successfully inserted into PyBossa");
				return true;
			} else {
				return false;
			}

		} else {
			logger.error("Task run was not inserted into MongoDB!");
			return false;
		}

	}

}
