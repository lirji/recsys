package com.recsys.common.experiment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExposureAttributionTest {

    @Test
    void encodeDecodePreservesBucketDelimitersAndIdentity() {
        ExposureAttribution value = new ExposureAttribution(
                7, 42, "cold;recall:plus;rank:onnx;rerank:mmr");

        ExposureAttribution decoded = ExposureAttribution.decode(value.encode()).orElseThrow();

        assertThat(decoded).isEqualTo(value);
        assertThat(decoded.matches(7, 42)).isTrue();
        assertThat(decoded.matches(7, 43)).isFalse();
    }

    @Test
    void malformedValuesAreRejected() {
        assertThat(ExposureAttribution.decode(null)).isEmpty();
        assertThat(ExposureAttribution.decode("v1:bad")).isEmpty();
        assertThat(ExposureAttribution.decode("v2:7:42:eA")).isEmpty();
    }
}
