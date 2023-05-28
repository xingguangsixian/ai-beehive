package cn.beehive.cell.midjourney.service.impl;

import cn.beehive.base.util.ForestRequestUtil;
import cn.beehive.base.util.ObjectMapperUtil;
import cn.beehive.cell.midjourney.config.MidjourneyConfig;
import cn.beehive.cell.midjourney.service.DiscordService;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Pair;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.http.Header;
import com.dtflys.forest.Forest;
import com.dtflys.forest.http.ForestRequest;
import com.dtflys.forest.http.ForestResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author hncboy
 * @date 2023/5/19
 * Discord 业务接口实现类
 */
@Slf4j
@Service
public class DiscordServiceImpl implements DiscordService {

    @Resource
    private MidjourneyConfig midjourneyConfig;

    @Resource
    private ObjectMapper objectMapper;

    private final String imagineParamsJson;
    private final String upscaleParamsJson;
    private final String variationParamsJson;
    private final String describeParamsJson;

    public DiscordServiceImpl() {
        this.imagineParamsJson = ResourceUtil.readUtf8Str("midjourney/imagine.json");
        this.upscaleParamsJson = ResourceUtil.readUtf8Str("midjourney/upscale.json");
        this.variationParamsJson = ResourceUtil.readUtf8Str("midjourney/variation.json");
        this.describeParamsJson = ResourceUtil.readUtf8Str("midjourney/describe.json");
    }

    @Override
    public ForestResponse<?> imagine(String prompt) {
        String requestBodyStr = imagineParamsJson
                .replace("$guild_id", midjourneyConfig.getGuildId())
                .replace("$channel_id", midjourneyConfig.getChannelId())
                .replace("$prompt", prompt);
        return executeRequest(midjourneyConfig.getDiscordApiUrl(), requestBodyStr);
    }

    @Override
    public ForestResponse<?> upscale(String discordMessageId, int index, String discordMessageHash) {
        String requestBodyStr = upscaleParamsJson
                .replace("$guild_id", midjourneyConfig.getGuildId())
                .replace("$channel_id", midjourneyConfig.getChannelId())
                .replace("$message_id", discordMessageId)
                .replace("$index", String.valueOf(index))
                .replace("$message_hash", discordMessageHash);
        return executeRequest(midjourneyConfig.getDiscordApiUrl(), requestBodyStr);
    }

    @Override
    public ForestResponse<?> variation(String discordMessageId, int index, String discordMessageHash) {
        String requestBodyStr = variationParamsJson
                .replace("$guild_id", midjourneyConfig.getGuildId())
                .replace("$channel_id", midjourneyConfig.getChannelId())
                .replace("$message_id", discordMessageId)
                .replace("$index", String.valueOf(index))
                .replace("$message_hash", discordMessageHash);
        return executeRequest(midjourneyConfig.getDiscordApiUrl(), requestBodyStr);
    }

    @Override
    public Pair<Boolean, String> uploadImage(String fileName, MultipartFile multipartFile) {
        try {
            // 构建请求 JsonNode
            JsonNode reuqestJsonNode = objectMapper.createObjectNode().set("files",
                    objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                            .put("filename", fileName)
                            .put("file_size", multipartFile.getSize())
                            .put("id", "0")));
            // 预请求要上传的图片
            ForestResponse<?> forestResponse = executeRequest(midjourneyConfig.getDiscordUploadUrl(), ObjectMapperUtil.toJson(reuqestJsonNode));
            if (forestResponse.isError()) {
                log.error("Midjourney describe 预处理图片失败，文件名：{}，响应消息： {}", fileName, forestResponse.getContent());
                return new Pair<>(false, "上传图片失败，请稍后重试");
            }
            // 解析响应内容
            JsonNode attachments = objectMapper.readTree(forestResponse.getContent()).get("attachments");
            if (attachments.size() == 0) {
                log.error("Midjourney describe 预处理图片响应 attachments 为空，文件名：{}，响应消息： {}", fileName, forestResponse.getContent(), forestResponse.getException());
                return new Pair<>(false, "上传图片失败，请稍后重试");

            }

            // 获取真实上传图片地址和文件名
            String uploadUrl = attachments.get(0).get("upload_url").asText();
            String uploadFilename = attachments.get(0).get("upload_filename").asText();

            // 构建真实上传图片请求
            ForestRequest<?> forestRequest = Forest.put(uploadUrl)
                    .setContentType(multipartFile.getContentType())
                    .addBody(multipartFile.getBytes())
                    .setUserAgent(midjourneyConfig.getUserAgent())
                    .addHeader(Header.CONTENT_LENGTH.name(), multipartFile.getSize());
            ForestRequestUtil.buildProxy(forestRequest);
            forestResponse = forestRequest.execute(ForestResponse.class);
            if (forestResponse.isError()) {
                log.error("Midjourney describe 真实上传图片失败，文件名：{}，响应消息： {}", fileName, forestResponse.getContent(), forestResponse.getException());
                return new Pair<>(false, "上传图片失败，请稍后重试");
            }

            return new Pair<>(true, uploadFilename);
        } catch (Exception e) {
            log.error("Midjourney describe 上传图片失败，文件名：{}", fileName, e);
            return new Pair<>(false, "上传图片失败，请稍后重试");
        }
    }

    @Override
    public ForestResponse<?> describe(String uploadFileName) {
        // 拆分文件名
        String fileName = CharSequenceUtil.subAfter(uploadFileName, "/", true);
        String requestBodyStr = describeParamsJson
                .replace("$guild_id", midjourneyConfig.getGuildId())
                .replace("$channel_id", midjourneyConfig.getChannelId())
                .replace("$file_name", fileName)
                .replace("$final_file_name", uploadFileName);
        return executeRequest(midjourneyConfig.getDiscordApiUrl(), requestBodyStr);
    }

    /**
     * 执行请求
     *
     * @param discordUrl     请求地址
     * @param requestBodyStr 请求参数
     * @return 响应
     */
    private ForestResponse<?> executeRequest(String discordUrl, String requestBodyStr) {
        // 构建请求
        ForestRequest<?> forestRequest = Forest.post(discordUrl)
                .contentTypeJson()
                .addHeader(Header.AUTHORIZATION.name(), midjourneyConfig.getUserToken())
                .setUserAgent(midjourneyConfig.getUserAgent())
                .addBody(requestBodyStr);

        ForestRequestUtil.buildProxy(forestRequest);

        // 发起请求
        return forestRequest.execute(ForestResponse.class);
    }
}