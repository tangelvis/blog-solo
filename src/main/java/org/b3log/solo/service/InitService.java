/*
 * Solo - A small and beautiful blogging system written in Java.
 * Copyright (c) 2010-2019, b3log.org & hacpai.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.solo.service;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.commons.lang.time.DateUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.plugin.PluginManager;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.repository.jdbc.util.Connections;
import org.b3log.latke.repository.jdbc.util.JdbcRepositories;
import org.b3log.latke.repository.jdbc.util.JdbcRepositories.CreateTableResult;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Ids;
import org.b3log.solo.SoloServletListener;
import org.b3log.solo.model.*;
import org.b3log.solo.model.Option.DefaultPreference;
import org.b3log.solo.repository.*;
import org.b3log.solo.util.Images;
import org.b3log.solo.util.Skins;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.ParseException;
import java.util.List;
import java.util.Set;

/**
 * Solo initialization service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.5.2.27, Jan 28, 2019
 * @since 0.4.0
 */
@Service
public class InitService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(InitService.class);

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Tag-Article repository.
     */
    @Inject
    private TagArticleRepository tagArticleRepository;

    /**
     * Archive date repository.
     */
    @Inject
    private ArchiveDateRepository archiveDateRepository;

    /**
     * Archive date-Article repository.
     */
    @Inject
    private ArchiveDateArticleRepository archiveDateArticleRepository;

    /**
     * Tag repository.
     */
    @Inject
    private TagRepository tagRepository;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    /**
     * Link repository.
     */
    @Inject
    private LinkRepository linkRepository;

    /**
     * Statistic management service.
     */
    @Inject
    private StatisticMgmtService statisticMgmtService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Plugin manager.
     */
    @Inject
    private PluginManager pluginManager;

    /**
     * Flag of init status.
     */
    private static boolean inited;

    /**
     * Flag of printed init prompt.
     */
    private static boolean printedInitMsg;

    /**
     * Determines Solo had been initialized.
     *
     * @return {@code true} if it had been initialized, {@code false} otherwise
     */
    public boolean isInited() {
        if (inited) {
            return true;
        }

        try (final Connection connection = Connections.getConnection()) {
            final PreparedStatement preparedStatement = connection.prepareStatement("SELECT COUNT(1) AS `c` FROM `" + userRepository.getName() + "`");
            final ResultSet resultSet = preparedStatement.executeQuery();
            resultSet.next();
            final int c = resultSet.getInt("c");
            inited = 0 < c;

            return inited;
        } catch (final Exception e) {
            if (!printedInitMsg) {
                LOGGER.log(Level.WARN, "Solo has not been initialized, please open your browser and visit [" + Latkes.getServePath() + "] to init Solo");
            }
            printedInitMsg = true;

            return false;
        }
    }

    /**
     * Initializes Solo.
     *
     * @param requestJSONObject the specified request json object, for example,
     *                          {
     *                          "userName": "",
     *                          "userEmail": "",
     *                          "userPassword": "", // Unhashed
     *                          "userAvatar": "" // optional
     *                          }
     * @throws ServiceException service exception
     */
    public void init(final JSONObject requestJSONObject) throws ServiceException {
        if (isInited()) {
            return;
        }

        LOGGER.log(Level.DEBUG, "Solo is running with database [{0}], creates all tables", Latkes.getRuntimeDatabase());

        if (Latkes.RuntimeDatabase.H2 == Latkes.getRuntimeDatabase()) {
            String dataDir = Latkes.getLocalProperty("jdbc.URL");
            dataDir = dataDir.replace("~", System.getProperty("user.home"));
            LOGGER.log(Level.INFO, "Your DATA will be stored in directory [" + dataDir + "], "
                    + "please pay more attention on it!");
        }

        final List<CreateTableResult> createTableResults = JdbcRepositories.initAllTables();
        for (final CreateTableResult createTableResult : createTableResults) {
            LOGGER.log(Level.DEBUG, "Create table result [tableName={0}, isSuccess={1}]",
                    createTableResult.getName(), createTableResult.isSuccess());
        }

        final Transaction transaction = userRepository.beginTransaction();
        try {
            initStatistic();
            initPreference(requestJSONObject);
            initReplyNotificationTemplate();
            initAdmin(requestJSONObject);
            initLink();
            helloWorld();

            transaction.commit();
        } catch (final Exception e) {

            throw new ServiceException("Initializes Solo failed: " + e.getMessage());
        } finally {
            if (transaction.isActive()) {
                transaction.rollback();
            }
        }

        pluginManager.load();
    }

    /**
     * Publishes the first article "Hello World" and the first comment with the specified locale.
     *
     * @throws Exception exception
     */
    private void helloWorld() throws Exception {
        final JSONObject article = new JSONObject();

        article.put(Article.ARTICLE_TITLE, langPropsService.get("helloWorld.title"));
        final String content = "![](" + Images.randImage() + "?imageView2/1/w/960/h/520/interlace/1/q/100) \n\n" +
                langPropsService.get("helloWorld.content");

        article.put(Article.ARTICLE_ABSTRACT, content);
        article.put(Article.ARTICLE_CONTENT, content);
        article.put(Article.ARTICLE_TAGS_REF, "Solo");
        article.put(Article.ARTICLE_PERMALINK, "/hello-solo");
        article.put(Article.ARTICLE_IS_PUBLISHED, true);
        article.put(Article.ARTICLE_HAD_BEEN_PUBLISHED, true);
        article.put(Article.ARTICLE_SIGN_ID, "1");
        article.put(Article.ARTICLE_COMMENT_COUNT, 1);
        article.put(Article.ARTICLE_VIEW_COUNT, 0);
        final JSONObject admin = userRepository.getAdmin();
        final long now = System.currentTimeMillis();
        article.put(Article.ARTICLE_CREATED, now);
        article.put(Article.ARTICLE_UPDATED, now);
        article.put(Article.ARTICLE_PUT_TOP, false);
        article.put(Article.ARTICLE_RANDOM_DOUBLE, Math.random());
        article.put(Article.ARTICLE_AUTHOR_ID, admin.optString(Keys.OBJECT_ID));
        article.put(Article.ARTICLE_COMMENTABLE, true);
        article.put(Article.ARTICLE_VIEW_PWD, "");
        article.put(Article.ARTICLE_EDITOR_TYPE, DefaultPreference.DEFAULT_EDITOR_TYPE);

        final String articleId = addHelloWorldArticle(article);

        final JSONObject comment = new JSONObject();
        comment.put(Keys.OBJECT_ID, articleId);
        comment.put(Comment.COMMENT_NAME, "Daniel");
        comment.put(Comment.COMMENT_EMAIL, "d@b3log.org");
        comment.put(Comment.COMMENT_URL, "https://hacpai.com/member/88250");
        comment.put(Comment.COMMENT_CONTENT, langPropsService.get("helloWorld.comment.content"));
        comment.put(Comment.COMMENT_ORIGINAL_COMMENT_ID, "");
        comment.put(Comment.COMMENT_ORIGINAL_COMMENT_NAME, "");
        comment.put(Comment.COMMENT_THUMBNAIL_URL, Solos.GRAVATAR + "59a5e8209c780307dbe9c9ba728073f5??s=60&r=G");
        comment.put(Comment.COMMENT_CREATED, now);
        comment.put(Comment.COMMENT_ON_ID, articleId);
        comment.put(Comment.COMMENT_ON_TYPE, Article.ARTICLE);
        final String commentId = Ids.genTimeMillisId();
        comment.put(Keys.OBJECT_ID, commentId);
        final String commentSharpURL = Comment.getCommentSharpURLForArticle(article, commentId);
        comment.put(Comment.COMMENT_SHARP_URL, commentSharpURL);

        commentRepository.add(comment);
    }

    /**
     * Adds the specified "Hello World" article.
     *
     * @param article the specified "Hello World" article
     * @return generated article id
     * @throws RepositoryException repository exception
     */
    private String addHelloWorldArticle(final JSONObject article) throws RepositoryException {
        final String ret = Ids.genTimeMillisId();

        try {
            article.put(Keys.OBJECT_ID, ret);

            final String tagsString = article.optString(Article.ARTICLE_TAGS_REF);
            final String[] tagTitles = tagsString.split(",");
            final JSONArray tags = tag(tagTitles, article);
            addTagArticleRelation(tags, article);
            archiveDate(article);
            articleRepository.add(article);
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Adds an article failed", e);

            throw new RepositoryException(e);
        }

        return ret;
    }

    /**
     * Archive the create date with the specified article.
     *
     * @param article the specified article, for example,
     *                {
     *                ....,
     *                "oId": "",
     *                "articleCreateDate": java.util.Date,
     *                ....
     *                }
     * @throws RepositoryException repository exception
     */
    public void archiveDate(final JSONObject article) throws RepositoryException {
        final long created = article.optLong(Article.ARTICLE_CREATED);
        final String createDateString = DateFormatUtils.format(created, "yyyy/MM");
        final JSONObject archiveDate = new JSONObject();

        try {
            archiveDate.put(ArchiveDate.ARCHIVE_TIME, DateUtils.parseDate(createDateString, new String[]{"yyyy/MM"}).getTime());
            archiveDateRepository.add(archiveDate);
        } catch (final ParseException e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);
            throw new RepositoryException(e);
        }

        final JSONObject archiveDateArticleRelation = new JSONObject();
        archiveDateArticleRelation.put(ArchiveDate.ARCHIVE_DATE + "_" + Keys.OBJECT_ID, archiveDate.optString(Keys.OBJECT_ID));
        archiveDateArticleRelation.put(Article.ARTICLE + "_" + Keys.OBJECT_ID, article.optString(Keys.OBJECT_ID));
        archiveDateArticleRepository.add(archiveDateArticleRelation);
    }

    /**
     * Adds relation of the specified tags and article.
     *
     * @param tags    the specified tags
     * @param article the specified article
     * @throws RepositoryException repository exception
     */
    private void addTagArticleRelation(final JSONArray tags, final JSONObject article) throws RepositoryException {
        for (int i = 0; i < tags.length(); i++) {
            final JSONObject tag = tags.optJSONObject(i);
            final JSONObject tagArticleRelation = new JSONObject();
            tagArticleRelation.put(Tag.TAG + "_" + Keys.OBJECT_ID, tag.optString(Keys.OBJECT_ID));
            tagArticleRelation.put(Article.ARTICLE + "_" + Keys.OBJECT_ID, article.optString(Keys.OBJECT_ID));
            tagArticleRepository.add(tagArticleRelation);
        }
    }

    /**
     * Tags the specified article with the specified tag titles.
     *
     * @param tagTitles the specified tag titles
     * @param article   the specified article
     * @return an array of tags
     * @throws RepositoryException repository exception
     */
    private JSONArray tag(final String[] tagTitles, final JSONObject article) throws RepositoryException {
        final JSONArray ret = new JSONArray();

        for (int i = 0; i < tagTitles.length; i++) {
            final String tagTitle = tagTitles[i].trim();
            final JSONObject tag = new JSONObject();

            LOGGER.log(Level.TRACE, "Found a new tag[title={0}] in article[title={1}]", tagTitle, article.optString(Article.ARTICLE_TITLE));
            tag.put(Tag.TAG_TITLE, tagTitle);
            final String tagId = tagRepository.add(tag);
            tag.put(Keys.OBJECT_ID, tagId);
            ret.put(tag);
        }

        return ret;
    }

    /**
     * Initializes administrator with the specified request json object, and then logins it.
     *
     * @param requestJSONObject the specified request json object, for example,
     *                          {
     *                          "userName": "",
     *                          "userEmail": "",
     *                          "userPassowrd": "", // Unhashed
     *                          "userAvatar": "" // optional
     *                          }
     * @throws Exception exception
     */
    private void initAdmin(final JSONObject requestJSONObject) throws Exception {
        LOGGER.debug("Initializing admin....");
        final JSONObject admin = new JSONObject();

        admin.put(User.USER_NAME, requestJSONObject.getString(User.USER_NAME));
        admin.put(User.USER_EMAIL, requestJSONObject.getString(User.USER_EMAIL));
        admin.put(User.USER_URL, Latkes.getServePath());
        admin.put(User.USER_ROLE, Role.ADMIN_ROLE);
        admin.put(User.USER_PASSWORD, DigestUtils.md5Hex(requestJSONObject.getString(User.USER_PASSWORD)));
        String avatar = requestJSONObject.optString(UserExt.USER_AVATAR);
        if (StringUtils.isBlank(avatar)) {
            avatar = Solos.getGravatarURL(requestJSONObject.getString(User.USER_EMAIL), "128");
        }
        admin.put(UserExt.USER_AVATAR, avatar);
        userRepository.add(admin);

        LOGGER.debug("Initialized admin");
    }

    /**
     * Initializes link.
     *
     * @throws Exception exception
     */
    private void initLink() throws Exception {
        final JSONObject link = new JSONObject();

        link.put(Link.LINK_TITLE, "黑客派");
        link.put(Link.LINK_ADDRESS, "https://hacpai.com");
        link.put(Link.LINK_DESCRIPTION, "黑客与画家的社区");
        final int maxOrder = linkRepository.getMaxOrder();
        link.put(Link.LINK_ORDER, maxOrder + 1);
        final String ret = linkRepository.add(link);
    }

    /**
     * Initializes statistic.
     *
     * @throws RepositoryException repository exception
     * @throws JSONException       json exception
     */
    private void initStatistic() throws RepositoryException, JSONException {
        LOGGER.debug("Initializing statistic....");

        final JSONObject statisticBlogViewCountOpt = new JSONObject();
        statisticBlogViewCountOpt.put(Keys.OBJECT_ID, Option.ID_C_STATISTIC_BLOG_VIEW_COUNT);
        statisticBlogViewCountOpt.put(Option.OPTION_VALUE, "0");
        statisticBlogViewCountOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_STATISTIC);
        optionRepository.add(statisticBlogViewCountOpt);

        LOGGER.debug("Initialized statistic");
    }

    /**
     * Initializes reply notification template.
     *
     * @throws Exception exception
     */
    private void initReplyNotificationTemplate() throws Exception {
        LOGGER.debug("Initializing reply notification template");

        final JSONObject replyNotificationTemplate = new JSONObject(DefaultPreference.DEFAULT_REPLY_NOTIFICATION_TEMPLATE);
        replyNotificationTemplate.put(Keys.OBJECT_ID, "replyNotificationTemplate");

        final JSONObject subjectOpt = new JSONObject();
        subjectOpt.put(Keys.OBJECT_ID, Option.ID_C_REPLY_NOTI_TPL_SUBJECT);
        subjectOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        subjectOpt.put(Option.OPTION_VALUE, replyNotificationTemplate.optString("subject"));
        optionRepository.add(subjectOpt);

        final JSONObject bodyOpt = new JSONObject();
        bodyOpt.put(Keys.OBJECT_ID, Option.ID_C_REPLY_NOTI_TPL_BODY);
        bodyOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        bodyOpt.put(Option.OPTION_VALUE, replyNotificationTemplate.optString("body"));
        optionRepository.add(bodyOpt);

        LOGGER.debug("Initialized reply notification template");
    }

    /**
     * Initializes preference.
     *
     * @param requestJSONObject the specified json object
     * @throws Exception exception
     */
    private void initPreference(final JSONObject requestJSONObject) throws Exception {
        LOGGER.debug("Initializing preference....");

        final JSONObject customVarsOpt = new JSONObject();
        customVarsOpt.put(Keys.OBJECT_ID, Option.ID_C_CUSTOM_VARS);
        customVarsOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        customVarsOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_CUSTOM_VARS);
        optionRepository.add(customVarsOpt);

        final JSONObject noticeBoardOpt = new JSONObject();
        noticeBoardOpt.put(Keys.OBJECT_ID, Option.ID_C_NOTICE_BOARD);
        noticeBoardOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        noticeBoardOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_NOTICE_BOARD);
        optionRepository.add(noticeBoardOpt);

        final JSONObject metaDescriptionOpt = new JSONObject();
        metaDescriptionOpt.put(Keys.OBJECT_ID, Option.ID_C_META_DESCRIPTION);
        metaDescriptionOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        metaDescriptionOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_META_DESCRIPTION);
        optionRepository.add(metaDescriptionOpt);

        final JSONObject metaKeywordsOpt = new JSONObject();
        metaKeywordsOpt.put(Keys.OBJECT_ID, Option.ID_C_META_KEYWORDS);
        metaKeywordsOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        metaKeywordsOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_META_KEYWORDS);
        optionRepository.add(metaKeywordsOpt);

        final JSONObject htmlHeadOpt = new JSONObject();
        htmlHeadOpt.put(Keys.OBJECT_ID, Option.ID_C_HTML_HEAD);
        htmlHeadOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        htmlHeadOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_HTML_HEAD);
        optionRepository.add(htmlHeadOpt);

        final JSONObject relevantArticlesDisplayCountOpt = new JSONObject();
        relevantArticlesDisplayCountOpt.put(Keys.OBJECT_ID, Option.ID_C_RELEVANT_ARTICLES_DISPLAY_CNT);
        relevantArticlesDisplayCountOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        relevantArticlesDisplayCountOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_RELEVANT_ARTICLES_DISPLAY_COUNT);
        optionRepository.add(relevantArticlesDisplayCountOpt);

        final JSONObject randomArticlesDisplayCountOpt = new JSONObject();
        randomArticlesDisplayCountOpt.put(Keys.OBJECT_ID, Option.ID_C_RANDOM_ARTICLES_DISPLAY_CNT);
        randomArticlesDisplayCountOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        randomArticlesDisplayCountOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_RANDOM_ARTICLES_DISPLAY_COUNT);
        optionRepository.add(randomArticlesDisplayCountOpt);

        final JSONObject externalRelevantArticlesDisplayCountOpt = new JSONObject();
        externalRelevantArticlesDisplayCountOpt.put(Keys.OBJECT_ID, Option.ID_C_EXTERNAL_RELEVANT_ARTICLES_DISPLAY_CNT);
        externalRelevantArticlesDisplayCountOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        externalRelevantArticlesDisplayCountOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_EXTERNAL_RELEVANT_ARTICLES_DISPLAY_COUNT);
        optionRepository.add(externalRelevantArticlesDisplayCountOpt);

        final JSONObject mostViewArticleDisplayCountOpt = new JSONObject();
        mostViewArticleDisplayCountOpt.put(Keys.OBJECT_ID, Option.ID_C_MOST_VIEW_ARTICLE_DISPLAY_CNT);
        mostViewArticleDisplayCountOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        mostViewArticleDisplayCountOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_MOST_VIEW_ARTICLES_DISPLAY_COUNT);
        optionRepository.add(mostViewArticleDisplayCountOpt);

        final JSONObject articleListDisplayCountOpt = new JSONObject();
        articleListDisplayCountOpt.put(Keys.OBJECT_ID, Option.ID_C_ARTICLE_LIST_DISPLAY_COUNT);
        articleListDisplayCountOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        articleListDisplayCountOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_ARTICLE_LIST_DISPLAY_COUNT);
        optionRepository.add(articleListDisplayCountOpt);

        final JSONObject articleListPaginationWindowSizeOpt = new JSONObject();
        articleListPaginationWindowSizeOpt.put(Keys.OBJECT_ID, Option.ID_C_ARTICLE_LIST_PAGINATION_WINDOW_SIZE);
        articleListPaginationWindowSizeOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        articleListPaginationWindowSizeOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_ARTICLE_LIST_PAGINATION_WINDOW_SIZE);
        optionRepository.add(articleListPaginationWindowSizeOpt);

        final JSONObject mostUsedTagDisplayCountOpt = new JSONObject();
        mostUsedTagDisplayCountOpt.put(Keys.OBJECT_ID, Option.ID_C_MOST_USED_TAG_DISPLAY_CNT);
        mostUsedTagDisplayCountOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        mostUsedTagDisplayCountOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_MOST_USED_TAG_DISPLAY_COUNT);
        optionRepository.add(mostUsedTagDisplayCountOpt);

        final JSONObject mostCommentArticleDisplayCountOpt = new JSONObject();
        mostCommentArticleDisplayCountOpt.put(Keys.OBJECT_ID, Option.ID_C_MOST_COMMENT_ARTICLE_DISPLAY_CNT);
        mostCommentArticleDisplayCountOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        mostCommentArticleDisplayCountOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_MOST_COMMENT_ARTICLE_DISPLAY_COUNT);
        optionRepository.add(mostCommentArticleDisplayCountOpt);

        final JSONObject recentArticleDisplayCountOpt = new JSONObject();
        recentArticleDisplayCountOpt.put(Keys.OBJECT_ID, Option.ID_C_RECENT_ARTICLE_DISPLAY_CNT);
        recentArticleDisplayCountOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        recentArticleDisplayCountOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_RECENT_ARTICLE_DISPLAY_COUNT);
        optionRepository.add(recentArticleDisplayCountOpt);

        final JSONObject recentCommentDisplayCountOpt = new JSONObject();
        recentCommentDisplayCountOpt.put(Keys.OBJECT_ID, Option.ID_C_RECENT_COMMENT_DISPLAY_CNT);
        recentCommentDisplayCountOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        recentCommentDisplayCountOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_RECENT_COMMENT_DISPLAY_COUNT);
        optionRepository.add(recentCommentDisplayCountOpt);

        final JSONObject blogTitleOpt = new JSONObject();
        blogTitleOpt.put(Keys.OBJECT_ID, Option.ID_C_BLOG_TITLE);
        blogTitleOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        blogTitleOpt.put(Option.OPTION_VALUE, requestJSONObject.optString(User.USER_NAME) + " 的个人博客");
        optionRepository.add(blogTitleOpt);

        final JSONObject blogSubtitleOpt = new JSONObject();
        blogSubtitleOpt.put(Keys.OBJECT_ID, Option.ID_C_BLOG_SUBTITLE);
        blogSubtitleOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        blogSubtitleOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_BLOG_SUBTITLE);
        optionRepository.add(blogSubtitleOpt);

        final JSONObject adminEmailOpt = new JSONObject();
        adminEmailOpt.put(Keys.OBJECT_ID, Option.ID_C_ADMIN_EMAIL);
        adminEmailOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        adminEmailOpt.put(Option.OPTION_VALUE, requestJSONObject.getString(User.USER_EMAIL));
        optionRepository.add(adminEmailOpt);

        final JSONObject localeStringOpt = new JSONObject();
        localeStringOpt.put(Keys.OBJECT_ID, Option.ID_C_LOCALE_STRING);
        localeStringOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        localeStringOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_LANGUAGE);
        optionRepository.add(localeStringOpt);

        final JSONObject enableArticleUpdateHintOpt = new JSONObject();
        enableArticleUpdateHintOpt.put(Keys.OBJECT_ID, Option.ID_C_ENABLE_ARTICLE_UPDATE_HINT);
        enableArticleUpdateHintOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        enableArticleUpdateHintOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_ENABLE_ARTICLE_UPDATE_HINT);
        optionRepository.add(enableArticleUpdateHintOpt);

        final JSONObject signsOpt = new JSONObject();
        signsOpt.put(Keys.OBJECT_ID, Option.ID_C_SIGNS);
        signsOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        signsOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_SIGNS);
        optionRepository.add(signsOpt);

        final JSONObject timeZoneIdOpt = new JSONObject();
        timeZoneIdOpt.put(Keys.OBJECT_ID, Option.ID_C_TIME_ZONE_ID);
        timeZoneIdOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        timeZoneIdOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_TIME_ZONE);
        optionRepository.add(timeZoneIdOpt);

        final JSONObject allowVisitDraftViaPermalinkOpt = new JSONObject();
        allowVisitDraftViaPermalinkOpt.put(Keys.OBJECT_ID, Option.ID_C_ALLOW_VISIT_DRAFT_VIA_PERMALINK);
        allowVisitDraftViaPermalinkOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        allowVisitDraftViaPermalinkOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_ALLOW_VISIT_DRAFT_VIA_PERMALINK);
        optionRepository.add(allowVisitDraftViaPermalinkOpt);

        final JSONObject allowRegisterOpt = new JSONObject();
        allowRegisterOpt.put(Keys.OBJECT_ID, Option.ID_C_ALLOW_REGISTER);
        allowRegisterOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        allowRegisterOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_ALLOW_REGISTER);
        optionRepository.add(allowRegisterOpt);

        final JSONObject commentableOpt = new JSONObject();
        commentableOpt.put(Keys.OBJECT_ID, Option.ID_C_COMMENTABLE);
        commentableOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        commentableOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_COMMENTABLE);
        optionRepository.add(commentableOpt);

        final JSONObject versionOpt = new JSONObject();
        versionOpt.put(Keys.OBJECT_ID, Option.ID_C_VERSION);
        versionOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        versionOpt.put(Option.OPTION_VALUE, SoloServletListener.VERSION);
        optionRepository.add(versionOpt);

        final JSONObject articleListStyleOpt = new JSONObject();
        articleListStyleOpt.put(Keys.OBJECT_ID, Option.ID_C_ARTICLE_LIST_STYLE);
        articleListStyleOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        articleListStyleOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_ARTICLE_LIST_STYLE);
        optionRepository.add(articleListStyleOpt);

        final JSONObject keyOfSoloOpt = new JSONObject();
        keyOfSoloOpt.put(Keys.OBJECT_ID, Option.ID_C_KEY_OF_SOLO);
        keyOfSoloOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        keyOfSoloOpt.put(Option.OPTION_VALUE, Ids.genTimeMillisId());
        optionRepository.add(keyOfSoloOpt);

        final JSONObject feedOutputModeOpt = new JSONObject();
        feedOutputModeOpt.put(Keys.OBJECT_ID, Option.ID_C_FEED_OUTPUT_MODE);
        feedOutputModeOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        feedOutputModeOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_FEED_OUTPUT_MODE);
        optionRepository.add(feedOutputModeOpt);

        final JSONObject feedOutputCntOpt = new JSONObject();
        feedOutputCntOpt.put(Keys.OBJECT_ID, Option.ID_C_FEED_OUTPUT_CNT);
        feedOutputCntOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        feedOutputCntOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_FEED_OUTPUT_CNT);
        optionRepository.add(feedOutputCntOpt);

        final JSONObject editorTypeOpt = new JSONObject();
        editorTypeOpt.put(Keys.OBJECT_ID, Option.ID_C_EDITOR_TYPE);
        editorTypeOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        editorTypeOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_EDITOR_TYPE);
        optionRepository.add(editorTypeOpt);

        final JSONObject footerContentOpt = new JSONObject();
        footerContentOpt.put(Keys.OBJECT_ID, Option.ID_C_FOOTER_CONTENT);
        footerContentOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        footerContentOpt.put(Option.OPTION_VALUE, DefaultPreference.DEFAULT_FOOTER_CONTENT);
        optionRepository.add(footerContentOpt);

        final String skinDirName = DefaultPreference.DEFAULT_SKIN_DIR_NAME;
        final JSONObject skinDirNameOpt = new JSONObject();
        skinDirNameOpt.put(Keys.OBJECT_ID, Option.ID_C_SKIN_DIR_NAME);
        skinDirNameOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        skinDirNameOpt.put(Option.OPTION_VALUE, skinDirName);
        optionRepository.add(skinDirNameOpt);

        final String skinName = Latkes.getSkinName(skinDirName);
        final JSONObject skinNameOpt = new JSONObject();
        skinNameOpt.put(Keys.OBJECT_ID, Option.ID_C_SKIN_NAME);
        skinNameOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        skinNameOpt.put(Option.OPTION_VALUE, skinName);
        optionRepository.add(skinNameOpt);

        final Set<String> skinDirNames = Skins.getSkinDirNames();
        final JSONArray skinArray = new JSONArray();
        for (final String dirName : skinDirNames) {
            final JSONObject skin = new JSONObject();
            skinArray.put(skin);

            final String name = Latkes.getSkinName(dirName);
            skin.put(Skin.SKIN_NAME, name);
            skin.put(Skin.SKIN_DIR_NAME, dirName);
        }

        final JSONObject skinsOpt = new JSONObject();
        skinsOpt.put(Keys.OBJECT_ID, Option.ID_C_SKINS);
        skinsOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
        skinsOpt.put(Option.OPTION_VALUE, skinArray.toString());
        optionRepository.add(skinsOpt);

        LOGGER.debug("Initialized preference");
    }

    /**
     * Sets archive date article repository with the specified archive date article repository.
     *
     * @param archiveDateArticleRepository the specified archive date article repository
     */
    public void setArchiveDateArticleRepository(final ArchiveDateArticleRepository archiveDateArticleRepository) {
        this.archiveDateArticleRepository = archiveDateArticleRepository;
    }

    /**
     * Sets archive date repository with the specified archive date repository.
     *
     * @param archiveDateRepository the specified archive date repository
     */
    public void setArchiveDateRepository(final ArchiveDateRepository archiveDateRepository) {
        this.archiveDateRepository = archiveDateRepository;
    }

    /**
     * Sets the article repository with the specified article repository.
     *
     * @param articleRepository the specified article repository
     */
    public void setArticleRepository(final ArticleRepository articleRepository) {
        this.articleRepository = articleRepository;
    }

    /**
     * Sets the user repository with the specified user repository.
     *
     * @param userRepository the specified user repository
     */
    public void setUserRepository(final UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Sets the tag repository with the specified tag repository.
     *
     * @param tagRepository the specified tag repository
     */
    public void setTagRepository(final TagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    /**
     * Sets the tag article repository with the specified tag article repository.
     *
     * @param tagArticleRepository the specified tag article repository
     */
    public void setTagArticleRepository(final TagArticleRepository tagArticleRepository) {
        this.tagArticleRepository = tagArticleRepository;
    }

    /**
     * Sets the comment repository with the specified comment repository.
     *
     * @param commentRepository the specified comment repository
     */
    public void setCommentRepository(final CommentRepository commentRepository) {
        this.commentRepository = commentRepository;
    }

    /**
     * Sets the language service with the specified language service.
     *
     * @param langPropsService the specified language service
     */
    public void setLangPropsService(final LangPropsService langPropsService) {
        this.langPropsService = langPropsService;
    }

    /**
     * Sets the plugin manager with the specified plugin manager.
     *
     * @param pluginManager the specified plugin manager
     */
    public void setPluginManager(final PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }
}
