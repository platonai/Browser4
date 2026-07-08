(function() {
    const imgCount = document.querySelectorAll('img').length;
    const linkCount = document.querySelectorAll('a').length;
    const formCount = document.querySelectorAll('form').length;
    console.log(`Images: ${imgCount}, Links: ${linkCount}, Forms: ${formCount}`);
    return { images: imgCount, links: linkCount, forms: formCount };
})()
