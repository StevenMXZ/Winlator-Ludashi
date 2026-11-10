#include "base/alignment.h"
#include "core/cursor/cursor_font.h"
#include "core/cursor/data.h"

CursorFont table[] = {
        X_X_cursor,
        X_X_cursor_mask,
        X_arrow,
        X_arrow_mask,
        X_based_arrow_down,
        X_based_arrow_down_mask,
        X_based_arrow_up,
        X_based_arrow_up_mask,
        X_boat,
        X_boat_mask,
        X_bogosity,
        X_bogosity_mask,
        X_bottom_left_corner,
        X_bottom_left_corner_mask,
        X_bottom_right_corner,
        X_bottom_right_corner_mask,
        X_bottom_side,
        X_bottom_side_mask,
        X_bottom_tee,
        X_bottom_tee_mask,
        X_box_spiral,
        X_box_spiral_mask,
        X_center_ptr,
        X_center_ptr_mask,
        X_circle,
        X_circle_mask,
        X_clock,
        X_clock_mask,
        X_coffee_mug,
        X_coffee_mug_mask,
        X_cross,
        X_cross_mask,
        X_cross_reverse,
        X_cross_reverse_mask,
        X_crosshair,
        X_crosshair_mask,
        X_diamond_cross,
        X_diamond_cross_mask,
        X_dot,
        X_dot_mask,
        X_dotbox,
        X_dotbox_mask,
        X_double_arrow,
        X_double_arrow_mask,
        X_draft_large,
        X_draft_large_mask,
        X_draft_small,
        X_draft_small_mask,
        X_draped_box,
        X_draped_box_mask,
        X_exchange,
        X_exchange_mask,
        X_fleur,
        X_fleur_mask,
        X_gobbler,
        X_gobbler_mask,
        X_gumby,
        X_gumby_mask,
        X_hand1,
        X_hand1_mask,
        X_hand2,
        X_hand2_mask,
        X_heart,
        X_heart_mask,
        X_icon,
        X_icon_mask,
        X_iron_cross,
        X_iron_cross_mask,
        X_left_ptr,
        X_left_ptr_mask,
        X_left_side,
        X_left_side_mask,
        X_left_tee,
        X_left_tee_mask,
        X_leftbutton,
        X_leftbutton_mask,
        X_ll_angle,
        X_ll_angle_mask,
        X_lr_angle,
        X_lr_angle_mask,
        X_man,
        X_man_mask,
        X_middlebutton,
        X_middlebutton_mask,
        X_mouse,
        X_mouse_mask,
        X_pencil,
        X_pencil_mask,
        X_pirate,
        X_pirate_mask,
        X_plus,
        X_plus_mask,
        X_question_arrow,
        X_question_arrow_mask,
        X_right_ptr,
        X_right_ptr_mask,
        X_right_side,
        X_right_side_mask,
        X_right_tee,
        X_right_tee_mask,
        X_rightbutton,
        X_rightbutton_mask,
        X_rtl_logo,
        X_rtl_logo_mask,
        X_sailboat,
        X_sailboat_mask,
        X_sb_down_arrow,
        X_sb_down_arrow_mask,
        X_sb_h_double_arrow,
        X_sb_h_double_arrow_mask,
        X_sb_left_arrow,
        X_sb_left_arrow_mask,
        X_sb_right_arrow,
        X_sb_right_arrow_mask,
        X_sb_up_arrow,
        X_sb_up_arrow_mask,
        X_sb_v_double_arrow,
        X_sb_v_double_arrow_mask,
        X_shuttle,
        X_shuttle_mask,
        X_sizing,
        X_sizing_mask,
        X_spider,
        X_spider_mask,
        X_spraycan,
        X_spraycan_mask,
        X_star,
        X_star_mask,
        X_target,
        X_target_mask,
        X_tcross,
        X_tcross_mask,
        X_top_left_arrow,
        X_top_left_arrow_mask,
        X_top_left_corner,
        X_top_left_corner_mask,
        X_top_right_corner,
        X_top_right_corner_mask,
        X_top_side,
        X_top_side_mask,
        X_top_tee,
        X_top_tee_mask,
        X_trek,
        X_trek_mask,
        X_ul_angle,
        X_ul_angle_mask,
        X_umbrella,
        X_umbrella_mask,
        X_ur_angle,
        X_ur_angle_mask,
        X_watch,
        X_watch_mask,
        X_xterm,
        X_xterm_mask,
};

std::vector<u8> CursorFont::ToRGBA() const {
    std::vector<u8> rgba(base::AlignUp(width * height, 4) * 4);
    DecompressGrayscale(width, height, data, rgba.data());
    return rgba;
}

const CursorFont& GetXCursorFont(u32 id) { return table[id]; }

bool DecompressGrayscale(u32 width, u32 height, const u8* compressed_data, u8* decompressed_data) {
    for (int i = 0; i < (width * height + 3) / 4; i++) {
        const u8 byte = compressed_data[i];
        for (int j = 0; j < 4; j++) {
            const u8 pixel = (byte >> (j * 2)) & 0x3;
            u32 rgba;
            switch (pixel) {
                case 0:
                    rgba = 0x00ffffff;
                    break;
                case 1:
                    rgba = 0xff000000;
                    break;
                case 2:
                    rgba = 0xff0000ff;
                    break;
                default:
                    throw std::runtime_error("Invalid pixel value");
            }
            memcpy(&decompressed_data[(i * 4 + j) * 4], &rgba, 4);
        }
    }
    return true;
}