# plugin-media-video-detection

Test the **browser4-media** plugin by detecting video elements on real web pages and exploring media download capabilities.

1. Navigate to `https://www.w3schools.com/html/html5_video.asp`. This page contains multiple `<video>` elements demonstrating HTML5 video features.

2. Use the `media.detectVideos` tool to scan the page for all video sources. Report:
   - Total number of video sources detected
   - For each video: tag name, source URL, MIME type, dimensions, and whether it has controls
   - How many are `<video>` tags vs `<source>` children vs iframe embeds
   - Whether any HLS or DASH streams were detected

3. Identify the direct MP4 video URL from the detected sources. Use `media.download` to download one of the detected MP4 videos. Verify the download succeeds:
   - `success` is true
   - `bytesDownloaded` > 0
   - The file exists on disk at the reported `filePath`

4. Navigate to `https://www.w3schools.com/html/html_youtube.asp`. This page embeds a YouTube iframe. Use `media.detectVideos` to check whether the YouTube embed is detected. Report what video sources are found on this page.

5. Navigate to `https://www.w3schools.com/html/tryit.asp?filename=tryhtml5_video`. This page has a `<video>` element inside an iframe. Use the browser's ability to evaluate JavaScript to check whether `<video>` elements are present in the page, then use `media.detectVideos` to scan the page from the backend's perspective.

6. If any video was downloaded in step 3, use `media.getInfo` on the downloaded file to probe its metadata. Report:
   - Format and duration
   - Resolution (width × height)
   - Codec and bitrate
   - Number of streams (video + audio)
