// Copyright (c) Microsoft Corporation.
// Licensed under the MIT License.

package com.microsoft.hydralab.center.controller;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.hydralab.center.service.StorageTokenManageService;
import com.microsoft.hydralab.center.service.SysTeamService;
import com.microsoft.hydralab.center.service.SysUserService;
import com.microsoft.hydralab.center.service.TestFileSetService;
import com.microsoft.hydralab.center.service.UserTeamManagementService;
import com.microsoft.hydralab.center.util.ClamAVScanner;
import com.microsoft.hydralab.common.entity.agent.Result;
import com.microsoft.hydralab.common.entity.center.SysTeam;
import com.microsoft.hydralab.common.entity.center.SysUser;
import com.microsoft.hydralab.common.entity.common.CriteriaType;
import com.microsoft.hydralab.common.entity.common.EntityType;
import com.microsoft.hydralab.common.entity.common.StorageFileInfo;
import com.microsoft.hydralab.common.entity.common.StorageFileInfo.ParserKey;
import com.microsoft.hydralab.common.entity.common.TestFileSet;
import com.microsoft.hydralab.common.entity.common.TestJsonInfo;
import com.microsoft.hydralab.common.file.impl.azure.SASData;
import com.microsoft.hydralab.common.util.AttachmentService;
import com.microsoft.hydralab.common.util.Const;
import com.microsoft.hydralab.common.util.FileUtil;
import com.microsoft.hydralab.common.util.HydraLabRuntimeException;
import com.microsoft.hydralab.common.util.LogUtils;
import com.microsoft.hydralab.common.util.PkgUtil.FILE_SUFFIX;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import static com.microsoft.hydralab.center.util.CenterConstant.CENTER_FILE_BASE_DIR;

@RestController
public class PackageSetController {
    private final Logger logger = LoggerFactory.getLogger(PackageSetController.class);
    private final SimpleDateFormat formatDate = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
    private final int messageLength = 200;
    @Resource
    AttachmentService attachmentService;
    @Resource
    TestFileSetService testFileSetService;
    @Resource
    private SysTeamService sysTeamService;
    @Resource
    private SysUserService sysUserService;
    @Resource
    private UserTeamManagementService userTeamManagementService;
    @Resource
    private StorageTokenManageService storageTokenManageService;

    /**
     * Authenticated USER:
     * 1) users with ROLE SUPER_ADMIN/ADMIN,
     * 2) members of the TEAM that fileSet is in
     */
    @PostMapping(value = {"/api/package/add"}, produces = MediaType.APPLICATION_JSON_VALUE)
    @SuppressWarnings("ParameterNumber")
    public Result add(@CurrentSecurityContext SysUser requestor,
                      @RequestParam(value = "teamName", required = false) String teamName,
                      @RequestParam(value = "commitId", required = false) String commitId,
                      @RequestParam(value = "commitCount", defaultValue = "-1") String commitCount,
                      @RequestParam(value = "commitMessage", defaultValue = "") String commitMessage,
                      @RequestParam(value = "buildType", defaultValue = "debug") String buildType,
                      @RequestParam("appFile") MultipartFile appFile,
                      @RequestParam(value = "appVersion", required = false) String appVersion, // required only for apps with param skipInstall = true
                      @RequestParam(value = "testAppFile", required = false) MultipartFile testAppFile) {
        if (requestor == null) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "unauthorized");
        }
        String localTeamName = teamName;
        if (StringUtils.isEmpty(teamName)) {
            localTeamName = requestor.getDefaultTeamName();
        }
        SysTeam team = sysTeamService.queryTeamByName(localTeamName);
        if (team == null) {
            return Result.error(HttpStatus.BAD_REQUEST.value(), "Team doesn't exist.");
        }
        if (!sysUserService.checkUserAdmin(requestor) && !userTeamManagementService.checkRequestorTeamRelation(requestor, team.getTeamId())) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "User doesn't belong to this Team");
        }
        if (appFile.isEmpty()) {
            return Result.error(HttpStatus.FORBIDDEN.value(), "apk file empty");
        }
        String localCommitId = commitId;
        if (!LogUtils.isLegalStr(commitId, Const.RegexString.COMMON_STR, true)) {
            localCommitId = "commitId";
        }
        String localBuildType = buildType;
        if (!LogUtils.isLegalStr(buildType, Const.RegexString.COMMON_STR, false)) {
            localBuildType = "debug";
        }
        int commitCountInt = Integer.parseInt(commitCount);
        String localCommitMessage = commitMessage.replaceAll("[\\t\\n\\r]", " ");
        if (localCommitMessage.length() > messageLength) {
            localCommitMessage = localCommitMessage.substring(0, messageLength);
        }
        logger.info("commitId: {}, commitMessage: {}, buildType: {}, commitCount: {}", localCommitId, localCommitMessage, localBuildType,
                commitCountInt);// CodeQL [java/log-injection] False Positive: Has verified the string by regular expression

        try {
            String relativeParent = FileUtil.getPathForToday();
            //Init test file set info
            TestFileSet testFileSet = new TestFileSet();
            testFileSet.setBuildType(localBuildType);
            testFileSet.setCommitId(localCommitId);
            testFileSet.setCommitMessage(localCommitMessage);
            testFileSet.setCommitCount(commitCount);
            testFileSet.setTeamId(team.getTeamId());
            testFileSet.setTeamName(team.getTeamName());

            //Save app file to server
            ClamAVScanner.getInstance().scan(appFile.getOriginalFilename(), appFile.getInputStream());
            File tempAppFile =
                    attachmentService.verifyAndSaveFile(appFile, CENTER_FILE_BASE_DIR + relativeParent, false, null,
                            new String[]{FILE_SUFFIX.APK_FILE, FILE_SUFFIX.IPA_FILE, FILE_SUFFIX.ZIP_FILE});
            StorageFileInfo appFileInfo = new StorageFileInfo(tempAppFile, relativeParent, StorageFileInfo.FileType.APP_FILE, team.getTeamId(), team.getTeamName());
            //Upload app file
            appFileInfo = attachmentService.addAttachment(testFileSet.getId(), EntityType.APP_FILE_SET, appFileInfo, tempAppFile, logger);
            JSONObject appFileParser = appFileInfo.getFileParser();
            testFileSet.setAppName(appFileParser.getString(ParserKey.APP_NAME));
            testFileSet.setPackageName(appFileParser.getString(ParserKey.PKG_NAME));
            if (StringUtils.isBlank(appVersion)) {
                testFileSet.setVersion(appFileParser.getString(ParserKey.VERSION));
            } else {
                testFileSet.setVersion(appVersion);
            }
            testFileSet.getAttachments().add(appFileInfo);

            //Save test app file to server if exist
            if (testAppFile != null && !testAppFile.isEmpty()) {
                ClamAVScanner.getInstance().scan(testAppFile.getOriginalFilename(), testAppFile.getInputStream());
                File tempTestAppFile = attachmentService.verifyAndSaveFile(testAppFile, CENTER_FILE_BASE_DIR + relativeParent, false, null,
                        new String[]{FILE_SUFFIX.APK_FILE, FILE_SUFFIX.JAR_FILE, FILE_SUFFIX.JSON_FILE, FILE_SUFFIX.ZIP_FILE});

                StorageFileInfo testAppFileInfo =
                        new StorageFileInfo(tempTestAppFile, relativeParent, StorageFileInfo.FileType.TEST_APP_FILE, team.getTeamId(), team.getTeamName());
                //Upload app file
                testAppFileInfo = attachmentService.addAttachment(testFileSet.getId(), EntityType.APP_FILE_SET, testAppFileInfo, tempTestAppFile, logger);
                testFileSet.getAttachments().add(testAppFileInfo);
            }

            //Save file set info to DB and memory
            testFileSetService.addTestFileSet(testFileSet);
            return Result.ok(testFileSet);
        } catch (HydraLabRuntimeException e) {
            return Result.error(e.getCode(), e);
        } catch (IOException e) {
            e.printStackTrace();
            return Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), e);
        }
    }

    /**
     * Authenticated USER:
     * 1) users with ROLE SUPER_ADMIN/ADMIN,
     * 2) members of the TEAM that fileSet is in
     */
    @GetMapping("/api/package/{fileSetId}")
    public Result<TestFileSet> getFileSetInfo(@CurrentSecurityContext SysUser requestor,
                                              @PathVariable(value = "fileSetId") String fileSetId) {
        if (requestor == null) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "unauthorized");
        }
        TestFileSet testFileSet = testFileSetService.getFileSetInfo(fileSetId);
        if (testFileSet == null) {
            return Result.error(HttpStatus.BAD_REQUEST.value(), "FileSetId is error!");
        }
        if (!sysUserService.checkUserAdmin(requestor) && !userTeamManagementService.checkRequestorTeamRelation(requestor, testFileSet.getTeamId())) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "Unauthorized, the TestFileSet doesn't belong to user's Teams");
        }

        return Result.ok(testFileSet);
    }

    /**
     * Authenticated USER: all
     * Data access:
     * 1) For users with ROLE SUPER_ADMIN/ADMIN, return all data.
     * 2) For the rest users, return the TestFileSet data that is in user's TEAMs
     */
    @PostMapping("/api/package/list")
    public Result<Page<TestFileSet>> list(@CurrentSecurityContext SysUser requestor,
                                          @RequestBody JSONObject data) {
        try {
            if (requestor == null) {
                return Result.error(HttpStatus.UNAUTHORIZED.value(), "unauthorized");
            }

            List<CriteriaType> criteriaTypes = new ArrayList<>();
            // filter all TestFileSets in TEAMs that user is in
            if (!sysUserService.checkUserAdmin(requestor)) {
                criteriaTypes = userTeamManagementService.formTeamIdCriteria(requestor.getTeamAdminMap());
                if (criteriaTypes.size() == 0) {
                    return Result.error(HttpStatus.UNAUTHORIZED.value(), "User belongs to no TEAM, please contact administrator for binding TEAM");
                }
            }

            int page = data.getIntValue("page");
            int pageSize = data.getIntValue("pageSize");
            if (pageSize <= 0) {
                pageSize = 30;
            }
            JSONArray queryParams = data.getJSONArray("queryParams");
            if (queryParams != null) {
                for (int i = 0; i < queryParams.size(); i++) {
                    CriteriaType temp = queryParams.getJSONObject(i).toJavaObject(CriteriaType.class);
                    criteriaTypes.add(temp);
                }
            }
            return Result.ok(testFileSetService.queryFileSets(page, pageSize, criteriaTypes));
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), e);
        }
    }

    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN','ADMIN')")
    @PostMapping("/api/package/addAgentPackage")
    public Result uploadAgentPackage(@RequestParam("packageFile") MultipartFile packageFile) {
        if (packageFile.isEmpty()) {
            return Result.error(HttpStatus.FORBIDDEN.value(), "package file empty");
        }

        String fileRelativeParent = FileUtil.getPathForToday();
        String parentDir = CENTER_FILE_BASE_DIR + fileRelativeParent;
        try {
            File savedPkg = attachmentService.verifyAndSaveFile(packageFile, parentDir, false, null, new String[]{FILE_SUFFIX.JAR_FILE});
            StorageFileInfo storageFileInfo = new StorageFileInfo(savedPkg, fileRelativeParent, StorageFileInfo.FileType.AGENT_PACKAGE, null, null);
            return Result.ok(attachmentService.saveFileInStorageAndDB(storageFileInfo, savedPkg, EntityType.AGENT_PACKAGE, logger));
        } catch (HydraLabRuntimeException e) {
            return Result.error(e.getCode(), e);
        } catch (Exception e) {
            return Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage());
        }

    }

    /**
     * Authenticated USER:
     * 1) users with ROLE SUPER_ADMIN/ADMIN,
     * 2) members of the TEAM that TestJsonInfo is in
     */
    @Deprecated
    // @PostMapping(value = {"/api/package/uploadJson"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Result uploadTestJson(@CurrentSecurityContext SysUser requestor,
                                 @RequestParam(value = "teamName", required = false) String teamName,
                                 @RequestParam(value = "packageName") String packageName,
                                 @RequestParam(value = "caseName") String caseName,
                                 @RequestParam(value = "testJsonFile") MultipartFile testJsonFile) {
        if (requestor == null) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "unauthorized");
        }
        if (!LogUtils.isLegalStr(packageName, Const.RegexString.PACKAGE_NAME, false)) {
            return Result.error(HttpStatus.BAD_REQUEST.value(), "The packagename is illegal");
        }
        String localTeamName = teamName;
        if (StringUtils.isEmpty(localTeamName)) {
            localTeamName = requestor.getDefaultTeamName();
        }
        SysTeam team = sysTeamService.queryTeamByName(localTeamName);
        if (team == null) {
            return Result.error(HttpStatus.BAD_REQUEST.value(), "Team doesn't exist.");
        }
        if (!sysUserService.checkUserAdmin(requestor) && !userTeamManagementService.checkRequestorTeamRelation(requestor, team.getTeamId())) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "User doesn't belong to this Team");
        }

        if (testJsonFile.isEmpty()) {
            return Result.error(HttpStatus.FORBIDDEN.value(), "test Json file empty");
        }

        String fileRelativePath = packageName + "/" + caseName;
        String parentDir = CENTER_FILE_BASE_DIR + fileRelativePath;
        try {
            TestJsonInfo testJsonInfo = new TestJsonInfo();
            testJsonInfo.setPackageName(packageName);
            testJsonInfo.setCaseName(caseName);
            testJsonInfo.setLatest(true);
            testJsonInfo.setTeamId(team.getTeamId());
            testJsonInfo.setTeamName(team.getTeamName());
            String newFileName = formatDate.format(testJsonInfo.getIngestTime()) + FILE_SUFFIX.JSON_FILE;
            File savedJson = attachmentService.verifyAndSaveFile(testJsonFile, parentDir, false, newFileName, new String[]{FILE_SUFFIX.JSON_FILE});
            String fileRelPath = fileRelativePath + "/" + savedJson.getName();
            testJsonInfo.setBlobPath(fileRelPath);

            return Result.ok(attachmentService.addTestJsonFile(testJsonInfo, savedJson, EntityType.TEST_JSON, logger));
        } catch (HydraLabRuntimeException e) {
            return Result.error(e.getCode(), e);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage());
        }
    }

    /**
     * Authenticated USER: all
     * Data access:
     * 1) For users with ROLE SUPER_ADMIN/ADMIN, return all data.
     * 2) For the rest users, return data that the JSON info is in the user's TEAMs
     */
    @Deprecated
    // @GetMapping("/api/package/testJsonList")
    public Result<List<TestJsonInfo>> testJsonList(@CurrentSecurityContext SysUser requestor) {
        if (requestor == null) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "unauthorized");
        }

        // filter all TestJsonInfos in TEAMs that user is in
        List<CriteriaType> criteriaTypes = new ArrayList<>();
        List<TestJsonInfo> testJsonInfoList;
        if (!sysUserService.checkUserAdmin(requestor)) {
            criteriaTypes = userTeamManagementService.formTeamIdCriteria(requestor.getTeamAdminMap());
            if (criteriaTypes.size() == 0) {
                return Result.error(HttpStatus.UNAUTHORIZED.value(), "User belongs to no TEAM, please contact administrator for binding TEAM");
            }
        }

        testJsonInfoList = attachmentService.getLatestTestJsonList(criteriaTypes);
        return Result.ok(testJsonInfoList);
    }

    /**
     * Authenticated USER:
     * 1) users with ROLE SUPER_ADMIN/ADMIN,
     * 2) members of the TEAM that TestJsonInfo is in
     */
    @Deprecated
    // @GetMapping("/api/package/testJsonHistory/{packageName}/{caseName}")
    public Result<List<TestJsonInfo>> testJsonHistory(@CurrentSecurityContext SysUser requestor, @PathVariable("packageName") String packageName,
                                                      @PathVariable("caseName") String caseName) {
        if (requestor == null) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "unauthorized");
        }
        List<TestJsonInfo> testJsonInfoList = attachmentService.getTestJsonHistory(packageName, caseName);
        if (!CollectionUtils.isEmpty(testJsonInfoList)) {
            String jsonTeamId = testJsonInfoList.get(0).getTeamId();
            if (!sysUserService.checkUserAdmin(requestor) && !userTeamManagementService.checkRequestorTeamRelation(requestor, jsonTeamId)) {
                return Result.error(HttpStatus.UNAUTHORIZED.value(), "Unauthorized, the TestJsonInfos don't belong to user's Teams");
            }
        }

        return Result.ok(testJsonInfoList);
    }

    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN','ADMIN')")
    @PostMapping("/api/package/queryAgentPackage")
    public Result queryAgentPackage() {

        return Result.ok(attachmentService.queryFileInfoByFileType(StorageFileInfo.FileType.AGENT_PACKAGE));
    }

    /**
     * Authenticated USER:
     * 1) users with ROLE SUPER_ADMIN/ADMIN,
     * 2) members of the TEAM that TestFileSet is in
     */
    @PostMapping(value = {"/api/package/addAttachment"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Result addAttachment(@CurrentSecurityContext SysUser requestor,
                                @RequestParam(value = "fileSetId") String fileSetId,
                                @RequestParam(value = "fileType") String fileType,
                                @RequestParam(value = "loadType", required = false) String loadType,
                                @RequestParam(value = "loadDir", required = false) String loadDir,
                                @RequestParam(value = "attachment") MultipartFile attachment) {
        if (requestor == null) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "unauthorized");
        }
        if (attachment.isEmpty()) {
            return Result.error(HttpStatus.BAD_REQUEST.value(), "attachment file empty");
        }
        TestFileSet testFileSet = testFileSetService.getFileSetInfo(fileSetId);
        if (testFileSet == null) {
            return Result.error(HttpStatus.BAD_REQUEST.value(), "Error fileSetId");
        }
        if (!sysUserService.checkUserAdmin(requestor) && !userTeamManagementService.checkRequestorTeamRelation(requestor, testFileSet.getTeamId())) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "Unauthorized, the TestFileSet doesn't belong to user's Teams");
        }

        String[] limitFileTypes = null;
        switch (fileType) {
            case StorageFileInfo.FileType.WINDOWS_APP:
                limitFileTypes = new String[]{FILE_SUFFIX.APPX_FILE};
                break;
            case StorageFileInfo.FileType.COMMON_FILE:
                Assert.notNull(loadType, "loadType is required");
                Assert.notNull(loadDir, "loadDir is required");
                Assert.isTrue(FileUtil.isLegalFolderPath(loadDir), "illegal loadDir");
                if (StorageFileInfo.LoadType.UNZIP.equals(loadType)) {
                    limitFileTypes = new String[]{FILE_SUFFIX.ZIP_FILE};
                }
                break;
            case StorageFileInfo.FileType.T2C_JSON_FILE:
                limitFileTypes = new String[]{FILE_SUFFIX.JSON_FILE};
                break;
            default:
                return Result.error(HttpStatus.BAD_REQUEST.value(), "Error fileType");
        }
        try {
            String originalFilename = attachment.getOriginalFilename();
            if (originalFilename.contains("..") || originalFilename.contains("/") || originalFilename.contains("\\")) {
                throw new HydraLabRuntimeException("Invalid filename");
            }
            String newFileName = FileUtil.getLegalFileName(originalFilename);
            String fileRelativeParent = FileUtil.getPathForToday();
            String parentDir = CENTER_FILE_BASE_DIR + fileRelativeParent;

            ClamAVScanner.getInstance().scan(attachment.getOriginalFilename(), attachment.getInputStream());
            File savedAttachment = attachmentService.verifyAndSaveFile(attachment, parentDir, false, newFileName, limitFileTypes);
            StorageFileInfo storageFileInfo =
                    new StorageFileInfo(savedAttachment, fileRelativeParent, fileType, loadType, loadDir, testFileSet.getTeamId(), testFileSet.getTeamName());
            attachmentService.addAttachment(fileSetId, EntityType.APP_FILE_SET, storageFileInfo, savedAttachment, logger);
            testFileSet.setAttachments(attachmentService.getAttachments(fileSetId, EntityType.APP_FILE_SET));
            testFileSetService.saveFileSetToMem(testFileSet);
            return Result.ok(testFileSet);
        } catch (HydraLabRuntimeException e) {
            e.printStackTrace();
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage());
        }
    }

    /**
     * Authenticated USER:
     * 1) users with ROLE SUPER_ADMIN/ADMIN,
     * 2) members of the TEAM that TestFileSet is in
     */
    @PostMapping(value = {"/api/package/removeAttachment"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Result removeAttachment(@CurrentSecurityContext SysUser requestor,
                                   @RequestParam(value = "fileSetId") String fileSetId,
                                   @RequestParam(value = "fileId") String fileId) {
        if (requestor == null) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "unauthorized");
        }
        TestFileSet testFileSet = testFileSetService.getFileSetInfo(fileSetId);
        if (testFileSet == null) {
            return Result.error(HttpStatus.BAD_REQUEST.value(), "Error fileSetId");
        }
        if (!sysUserService.checkUserAdmin(requestor) && !userTeamManagementService.checkRequestorTeamRelation(requestor, testFileSet.getTeamId())) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "Unauthorized, the TestFileSet doesn't belong to user's Teams");
        }

        attachmentService.removeAttachment(fileSetId, EntityType.APP_FILE_SET, fileId);
        testFileSet.setAttachments(attachmentService.getAttachments(fileSetId, EntityType.APP_FILE_SET));
        testFileSetService.saveFileSetToMem(testFileSet);
        return Result.ok(testFileSet);
    }

    @Deprecated
    @GetMapping("/api/package/getSAS")
    public Result<SASData> generateReadSAS(@CurrentSecurityContext SysUser requestor) {
        if (requestor == null) {
            return Result.error(HttpStatus.UNAUTHORIZED.value(), "unauthorized");
        }
        return Result.ok((SASData) storageTokenManageService.temporaryGetReadSAS(requestor.getMailAddress()));
    }
}
