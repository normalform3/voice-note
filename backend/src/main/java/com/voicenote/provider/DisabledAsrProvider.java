package com.voicenote.provider;

import com.voicenote.domain.AudioBlob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.dashscope.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledAsrProvider implements AsrProvider {
    @Override public AsrSubmission submit(AudioBlob audio, AsrOptions options) { throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_PROVIDER_DISABLED", "DashScope is disabled; configure DASHSCOPE_ENABLED and DASHSCOPE_API_KEY"); }
    @Override public AsrPollResult poll(String providerTaskId) { throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_PROVIDER_DISABLED", "DashScope is disabled"); }
}
