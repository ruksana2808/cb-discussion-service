package com.igot.cb.pores.util;

/**
 * @author Mahesh RV
 */
public class Constants {

    public static final String KEYSPACE_SUNBIRD = "sunbird";
    public static final String CORE_CONNECTIONS_PER_HOST_FOR_LOCAL = "coreConnectionsPerHostForLocal";
    public static final String CORE_CONNECTIONS_PER_HOST_FOR_REMOTE = "coreConnectionsPerHostForRemote";
    public static final String MAX_CONNECTIONS_PER_HOST_FOR_LOCAL = "maxConnectionsPerHostForLocal";
    public static final String MAX_CONNECTIONS_PER_HOST_FOR_REMOTE = "maxConnectionsPerHostForRemote";
    public static final String MAX_REQUEST_PER_CONNECTION = "maxRequestsPerConnection";
    public static final String HEARTBEAT_INTERVAL = "heartbeatIntervalSeconds";
    public static final String POOL_TIMEOUT = "poolTimeoutMillis";
    public static final String CASSANDRA_CONFIG_HOST = "cassandra.config.host";
    public static final String SUNBIRD_CASSANDRA_CONSISTENCY_LEVEL = "LOCAL_QUORUM";
    public static final String EXCEPTION_MSG_FETCH = "Exception occurred while fetching record from ";
    public static final String INSERT_INTO = "INSERT INTO ";
    public static final String DOT = ".";
    public static final String OPEN_BRACE = "(";
    public static final String VALUES_WITH_BRACE = ") VALUES (";
    public static final String QUE_MARK = "?";
    public static final String COMMA = ",";
    public static final String CLOSING_BRACE = ");";
    public static final String RESPONSE = "response";
    public static final String SUCCESS = "success";
    public static final String FAILED = "Failed";
    public static final String ERROR_MESSAGE = "errmsg";
    public static final String INDEX_TYPE = "_doc";
    public static final String REDIS_KEY_PREFIX = "cbpores_";
    public static final String KEYWORD = ".keyword";
    public static final String ASC = "asc";
    public static final String DOT_SEPARATOR = ".";
    public static final String SHA_256_WITH_RSA = "SHA256withRSA";
    public static final String UNAUTHORIZED = "Unauthorized";
    public static final String SUB = "sub";
    public static final String SSO_URL = "sso.url";
    public static final String SSO_REALM = "sso.realm";
    public static final String ACCESS_TOKEN_PUBLICKEY_BASEPATH = "accesstoken.publickey.basepath";
    public static final String ID = "id";
    public static final String FETCH_RESULT_CONSTANT = ".fetchResult:";
    public static final String URI_CONSTANT = "URI: ";
    public static final String REQUEST_CONSTANT = "Request: ";
    public static final String RESPONSE_CONSTANT = "Response: ";
    public static final String SEARCH_OPERATION_LESS_THAN = "<";
    public static final String SEARCH_OPERATION_GREATER_THAN = ">";
    public static final String SEARCH_OPERATION_LESS_THAN_EQUALS = "<=";
    public static final String SEARCH_OPERATION_GREATER_THAN_EQUALS = ">=";
    public static final String MUST= "must";
    public static final String FILTER= "filter";
    public static final String MUST_NOT="must_not";
    public static final String SHOULD= "should";
    public static final String BOOL="bool";
    public static final String TERM="term";
    public static final String TERMS="terms";
    public static final String MATCH="match";
    public static final String RANGE="range";
    public static final String UNSUPPORTED_QUERY="Unsupported query type";
    public static final String UNSUPPORTED_RANGE= "Unsupported range condition";
    public static final String UPDATE = "UPDATE ";
    public static final String SET = " SET ";
    public static final String WHERE_ID = "where id";
    public static final String EQUAL_WITH_QUE_MARK = " = ? ";
    public static final String SEMICOLON = ";";
    public static final String USER = "user";
    public static final String UNKNOWN_IDENTIFIER = "Unknown identifier ";
    public static final String EXCEPTION_MSG_UPDATE = "Exception occurred while updating record to ";
    public static final String DISCUSSION_VALIDATION_FILE = "/payloadValidation/discussionValidation.json";
    public static final String FAILED_TO_CREATE_DISCUSSION = "Failed to create the discussion";
    public static final String SEARCH_RESULTS = "search_results";
    public static final String DISCUSSION_ID = "discussionId";
    public static final  String DISCUSSION_IS_NOT_ACTIVE = "Discussion is not active";
    public static final String DISCUSSION_UPDATE_VALIDATION_FILE ="/payloadValidation/discussionUpdateValidation.json";
    public static final String USER_DISCUSSION_VOTES = "user_discussion_votes";
    public static final String DISCUSSION_ID_KEY = "discussionid";
    public static final String EXCEPTION_MSG_DELETE = "Exception occurred while deleting record from ";
    public static final String VOTE_COUNT = "voteCount";
    public static final String VOTE_TYPE = "votetype";
    public static final String TAGS = "tags";
    public static final String TARGET_TOPIC = "targetTopic";
    public static final String UP = "up";
    public static final String DOWN = "down";
    public static final String DISCUSSION_CACHE_PREFIX = "discussion_";
    public static final String ANSWER_POSTS = "answerPosts";
    public static final String VOTETYPE= "voteType";
    public static final String USERID= "userid";
    public static final String DISCUSSION_IS_INACTIVE = "Discussion is inactive.";
    public static final String FAILED_TO_VOTE = "failed to update user vote";
    public static final String USER_ALREADY_VOTED = "User already voted %s";
    public static final String MINIMUM_CHARACTERS_NEEDED= "Minimum 3 characters are required to search";
    public static final String FAILED_TO_DELETE_DISCUSSION = "failed to delete discussion";
    public static final String API_VERSION_1 = "1.0";
    public static final String INVALID_AUTH_TOKEN = "invalid auth token Please supply a valid auth token";
    public static final String CREATED_BY = "createdBy";
    public static final String CREATED_ON = "createdOn";
    public static final String ID_NOT_FOUND = "Id not found";
    public static final String INVALID_ID = "Invalid Id";
    public static final String TYPE = "type";
    public static final String TITLE = "title";
    public static final String DESCRIPTION_PAYLOAD = "description";
    public static final String IS_ACTIVE = "isActive";
    public static final String DELETED_SUCCESSFULLY = "deleted successfully";
    public static final String NO_DATA_FOUND = "No data found";
    public static final String USER_ID_RQST = "userId";
    public static final String REQUEST_PAYLOAD = "requestPayload";
    public static final String JWT_SECRET_KEY = "demand_search_result";
    public static final String FAILED_CONST = "FAILED";
    public static final String X_AUTH_TOKEN = "x-authenticated-user-token";
    public static final String UPDATED_ON = "updatedOn";
    public static final String MEDIA = "mediaUrls";
    public static final String USER_PREFIX = "user:" ;
    public static final String USER_TABLE = "user";
    public static final String PROFILE_DETAILS = "profiledetails";
    public static final String FIRST_NAME = "firstname";
    public static final String USER_ID_KEY = "user_id";
    public static final String FIRST_NAME_KEY = "first_name";
    public static final String PROFILE_IMG_KEY = "user_profile_img_url";
    public static final String PROFILE_IMG = "profileImageUrl";
    public static final String DESIGNATION_KEY = "designation";
    public static final String EMPLOYMENT_DETAILS = "employmentDetails";
    public static final String DEPARTMENT_KEY = "departmentName";
    public static final String DEPARTMENT = "department";
    public static final String DISCUSSION_ANSWER_POST_VALIDATION_FILE = "/payloadValidation/discussionAnswerPostValidation.json";
    public static final String PARENT_DISCUSSION_ID = "parentDiscussionId";
    public static final String FAILED_TO_CREATE_ANSWER_POST = "Failed to create the answer post";
    public static final String INVALID_PARENT_DISCUSSION_ID = "invalid Parent Discussion Id Please provide a valid discussion id";
    public static final String ANSWER_POST = "answerPost";
    public static final String ANSWER_POST_COUNT = "answerPostCount";
    public static final String DISCUSSION_NOT_FOUND = "DiscussionId not found Please provide a valid discussion id";
    public static final String UP_VOTE_COUNT = "upVoteCount";
    public static final String DOWN_VOTE_COUNT = "downVoteCount";
    public static final String DISCUSSION_VOTE_API = "discussion.vote";
    public static final String STATUS = "status";
    public static final String REPORTED_BY = "reportedBy";
    public static final String SUSPENDED = "suspended";
    public static final String REPORTED_REASON = "reportedDueTo";
    public static final String OTHER_REASON = "otherReasons";
    public static final String ACTIVE = "active";
    public static final String DISCUSSION_SUSPENDED = "discussion Already suspended";
    public static final String DISCUSSION_REPORT_FAILED = "Failed to report discussion";
    public static final String DISCUSSION_UPLOAD_FILE = "api.discussion.uploadFile";
    public static final String NAME = "name";
    public static final String URL = "url";
    public static final String UPLOAD_FILE = "api.file.upload";
    public static final String DISCUSSION_FILE_EMPTY = "File is empty";
    public static final String ADDITIONAL_REPORT_REASONS = "additionalReportReasons";
    public static final String OTHERS = "Others";
    public static final String PARENT_ANSWER_POST_ID_ERROR = "parentAnswerPostId cannot be of type answerPost";
    public static final String PARENT_DISCUSSION_ID_ERROR = "parentDiscussion is suspended, Please provide a valid parentDiscussion id";
    public static final String ELASTICSEARCH = "elasticsearch";
    public static final String DISCUSSION_ANSWER_POST = "discussion/answerPosts";
    public static final String REDIS = "redis";
    public static final String DISCUSSION_CREATE = "discussion/create";
    public static final String POSTGRES = "postgres";
    public static final String DISCUSSION_UPDATE = "discussion/update";
    public static final String READ = "read";
    public static final String INSERT = "insert";
    public static final String DISCUSSION_SEARCH = "discussion/search";
    public static final String CASSANDRA = "cassandra";
    public static final String DISCUSSION_READ = "discussion/read";
    public static final String UPDATE_KEY = "update";
    public static final String SEARCHTAGS = "searchTags";
    public static final String INVALID_COMMUNITY_ID = "Invalid communityId";
    public static final String COMMUNITY_ID = "communityId";
    public static final String COMMUNITY_ID_CANNOT_BE_UPDATED = "communityId cannot be updated";
    public static final String INVALID_DISCUSSION_ID = "Invalid discussionId";
    public static final String ANSWER_POST_ID = "answerPostId";
    public static final String INVALID_ANSWER_POST_ID = "Invalid answerPostId";
    public static final String FAILED_TO_UPDATE_ANSWER_POST = "Failed to update the answer post";
    public static final String ANSWER_POST_UPDATE_VALIDATION_FILE = "/payloadValidation/answerPostUpdateValidation.json";
    public static final String INCREMENT = "increment";
    public static final String DECREMENT = "decrement";
    public static final String POST = "post";
    public static final String USER_COMMUNITY = "user_community";
    public static final String USER_NOT_PART_OF_COMMUNITY = "User is not part of the community";
    public static final String DISCUSSION_BOOKMARKS = "user_post_bookmarks";
    public static final String ALREADY_BOOKMARKED = "Already bookmarked";
    public static final String DISCUSSION_BOOKMARK_FAILED = "Failed to bookmark the post";
    public static final String DISCUSSION_UN_BOOKMARK_FAILED = "Failed to unBookmark the post";
    public static final String NO_DISCUSSIONS_FOUND = "No discussions found";
    public static final String DISCUSSION_BOOKMARK_FETCH_FAILED = "Failed to fetch bookmarked discussions";
    public static final String DISCUSSIONS = "discussions";
    public static final String FILES = "files";
    public static final String DISCUSSION_UPLOAD_FILE_FETCH_FAILED = "Failed to fetch uploaded files";
    public static final String USER_REPORTED_POSTS= "user_reported_posts";
    public static final String POST_REPORTED_BY_USER = "post_reportedby_user";
    public static final String REASON = "reason";
    public static final String REPORTED = "reported";

    private Constants() {
    }
}
