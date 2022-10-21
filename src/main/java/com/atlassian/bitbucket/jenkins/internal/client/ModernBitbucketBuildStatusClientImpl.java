package com.atlassian.bitbucket.jenkins.internal.client;

import com.atlassian.bitbucket.jenkins.internal.http.RetryOnRateLimitConfig;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketBuildStatus;
import com.atlassian.bitbucket.jenkins.internal.provider.DefaultInstanceKeyPairProvider;
import com.atlassian.bitbucket.jenkins.internal.provider.InstanceKeyPairProvider;
import com.google.common.annotations.VisibleForTesting;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.jenkinsci.plugins.displayurlapi.DisplayURLProvider;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.stripToNull;

public class ModernBitbucketBuildStatusClientImpl implements BitbucketBuildStatusClient {

    private static final String BASE_URL_HEADER_ID = "base-url";
    private static final String BUILD_STATUS_SIGNATURE_ALGORITHM_ID = "BBS-Signature-Algorithm";
    private static final String BUILD_STATUS_SIGNATURE_ID = "BBS-Signature";
    private static final String BUILD_STATUS_VERSION = "1.0";
    private static final String SIGNING_ALGORITHM = "SHA256";
    private static final Logger LOGGER = Logger.getLogger(ModernBitbucketBuildStatusClientImpl.class.getName());

    private final BitbucketRequestExecutor bitbucketRequestExecutor;
    private final DisplayURLProvider displayURLProvider;
    private final InstanceKeyPairProvider instanceKeyPairProvider;
    private final String projectKey;
    private final String repoSlug;
    private final String revisionSha;
    private final boolean supportsCancelledState;

    @VisibleForTesting
    ModernBitbucketBuildStatusClientImpl(BitbucketRequestExecutor bitbucketRequestExecutor, String projectKey,
                                         String repoSlug, String revisionSha,
                                         InstanceKeyPairProvider instanceKeyPairProvider,
                                         DisplayURLProvider displayURLProvider,
                                         boolean supportsCancelledState) {
        this.bitbucketRequestExecutor = requireNonNull(bitbucketRequestExecutor, "bitbucketRequestExecutor");
        this.instanceKeyPairProvider = requireNonNull(instanceKeyPairProvider, "instanceIdentityProvider");
        this.revisionSha = requireNonNull(stripToNull(revisionSha), "revisionSha");
        this.projectKey = requireNonNull(stripToNull(projectKey), "projectKey");
        this.repoSlug = requireNonNull(stripToNull(repoSlug), "repoSlug");
        this.displayURLProvider = requireNonNull(displayURLProvider, "displayURLProvider");
        this.supportsCancelledState = supportsCancelledState;
    }

    ModernBitbucketBuildStatusClientImpl(BitbucketRequestExecutor bitbucketRequestExecutor, String projectKey,
                                         String repoSlug, String revisionSha, boolean supportsCancelledState) {
        this(bitbucketRequestExecutor, projectKey, repoSlug, revisionSha, new DefaultInstanceKeyPairProvider(),
                DisplayURLProvider.get(), supportsCancelledState);
    }

    @Override
    public void post(BitbucketBuildStatus.Builder buildStatusBuilder, Consumer<BitbucketBuildStatus> beforePost) {
        if (!supportsCancelledState) {
            buildStatusBuilder.noCancelledState();
        }
        BitbucketBuildStatus buildStatus = buildStatusBuilder.build();
        
        beforePost.accept(buildStatus);
        
        HttpUrl url = bitbucketRequestExecutor.getBaseUrl().newBuilder()
                .addPathSegment("rest")
                .addPathSegment("api")
                .addPathSegment(BUILD_STATUS_VERSION)
                .addPathSegment("projects")
                .addPathSegment(projectKey)
                .addPathSegment("repos")
                .addPathSegment(repoSlug)
                .addPathSegment("commits")
                .addPathSegment(revisionSha)
                .addPathSegment("builds")
                .build();
        bitbucketRequestExecutor.makePostRequest(url, buildStatus, builder -> generateHeaders(builder, buildStatus),
                new RetryOnRateLimitConfig(3));
    }

    private void generateHeaders(Request.Builder builder, BitbucketBuildStatus buildStatus) {
        Map<String, String> headers = new HashMap<>();
        headers.put(BASE_URL_HEADER_ID, displayURLProvider.getRoot());
        RSAPrivateKey key = instanceKeyPairProvider.getPrivate();
        String algorithm = SIGNING_ALGORITHM + "with" + key.getAlgorithm();

        try {
            Signature sig = Signature.getInstance(algorithm);
            sig.initSign(key);

            sig.update(buildStatus.getKey().getBytes(UTF_8));
            if (buildStatus.getRef() != null) {
                sig.update(buildStatus.getRef().getBytes(UTF_8));
            }
            sig.update(buildStatus.getState().getBytes(UTF_8));
            sig.update(buildStatus.getUrl().getBytes(UTF_8));

            headers.put(BUILD_STATUS_SIGNATURE_ID, Base64.getEncoder().encodeToString(sig.sign()));
            headers.put(BUILD_STATUS_SIGNATURE_ALGORITHM_ID, algorithm);
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
            LOGGER.log(Level.WARNING, "Error signing build status, continuing without signature:", e);
            return;
        }
        headers.entrySet().forEach(entry -> builder.header(entry.getKey(), entry.getValue()));
    }
}
