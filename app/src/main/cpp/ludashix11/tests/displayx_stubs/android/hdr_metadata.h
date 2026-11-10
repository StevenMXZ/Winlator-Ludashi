#pragma once
struct AXYColor { float x{}, y{}; };
struct AHdrMetadata_smpte2086 { AXYColor displayPrimaryRed, displayPrimaryGreen, displayPrimaryBlue, whitePoint; float maxLuminance{}, minLuminance{}; };
struct AHdrMetadata_cta861_3 { float maxContentLightLevel{}, maxFrameAverageLightLevel{}; };
