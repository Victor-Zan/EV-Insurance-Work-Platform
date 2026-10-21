# 登录页透明 PNG 素材

2026-10-21，通过内置 ImageGen 编辑用户提供的三张 JPG，输出透明 PNG；原图不覆盖。成品保存在 `admin-web/src/assets/login-{battery,gear,wrench}.png`，不是 SVG 替代图。PNG 原始输出未二次改变像素或透明通道。

## 电池

Use case: background-extraction. Asset type: transparent PNG decoration for an insurance repair platform login page. Edit target: the supplied upright silver cutaway battery image. Remove the white background completely to actual alpha transparency, preserving the exact silver metal casing, five distinct white/silver internal battery segments, proportions and fine mechanical detail. Rotate the entire battery 90 degrees clockwise so it is horizontal with the positive terminal pointing RIGHT. Keep the whole battery visible centered with very little transparent padding, landscape canvas. Keep the internal five silver cells neutral with no colored charge yet; animation will be added by the website. No floor, no cast shadow, no text, no checkerboard drawn in the image. Preserve the reference design as faithfully as possible.

## 齿轮

Use case: precise-object-edit. Asset type: one transparent PNG silver metal gear for a CSS animated gear assembly. Reference/edit target: the supplied image of four silver mechanical gears. Extract/reconstruct just ONE standalone complete gear matching the large left gear's brushed silver metal, rim, hollow spoke cutouts and central concentric bearing. Change camera to EXACT front-on orthographic view along its axle: perfectly circular outer boundary and perfectly concentric bearing. No visible long protruding shaft, no perspective ellipse. Exactly 24 evenly spaced functional teeth all around; six evenly spaced spoke cutouts. The circular gear itself must be centered exactly in a square canvas, with even transparent margin on all four sides, outer diameter about 90% of canvas. Entire object visible, no other gears, no overlap, no text. Actual transparent background and transparent holes, no floor or cast shadows. High quality silver mechanical illustration faithful to the provided reference, not a simple flat icon.

## 扳手

Use case: background-extraction. Asset type: transparent PNG static decoration for a repair platform login page. Edit target: the supplied silver combination wrench image. Remove only the white background to actual alpha transparency, including the open-end jaw gap and ring-end hole. Preserve exactly the single wrench silhouette, proportions, orientation (open jaw left, ring right, slightly sloping downward toward right), brushed silver shading, fine edges and details. Keep the whole wrench visible on a wide landscape canvas, tight transparent padding. No additional objects, no floor, no shadows outside the original object, no text, no watermark, no drawn checkerboard. Faithful cutout of the supplied image.
