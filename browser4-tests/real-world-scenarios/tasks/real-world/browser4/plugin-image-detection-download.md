# plugin-image-detection-download

Test the **browser4-images** plugin by detecting and downloading images from a real website.

1. Navigate to `https://en.wikipedia.org/wiki/Gallery_of_sovereign_state_flags`. This page contains hundreds of flag images — ideal for image detection testing.

2. Use the `image.detectImages` tool to scan the page for all image sources. Request only images at least 100px wide and 60px tall to filter out icons and tracking pixels.

3. Report:
   - Total number of images detected
   - How many are `<img>` tags vs CSS backgrounds vs meta tags
   - How many images pass the minimum dimension filters
   - The top 5 largest images by dimensions

4. Use the `image.download` tool to download the first 3 high-quality flag images (choose PNG or SVG flags with clear country representation). Verify each download succeeds.

5. Use the `image.downloadAll` tool to bulk-download all detected images with `minWidth: 200` and `minHeight: 120`. Report the download summary: total attempted, successful, failed, and total bytes.

6. Verify that the downloaded files exist on disk and have reasonable file sizes (> 1 KB each).
