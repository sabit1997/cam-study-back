package com.camstudy.backend.dto;

import com.camstudy.backend.entity.WindowType;
import lombok.Data;

@Data
public class WindowPatchDto {
    private WindowType type;  // nullable
    private String url;       // nullable
    private Integer x;        // nullable
    private Integer y;        // nullable
    private Integer width;    // nullable
    private Integer height;   // nullable
    private Integer zIndex;   // nullable
}
