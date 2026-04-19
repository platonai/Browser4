// Price loading functionality
let priceLoaded = false;

// Initialize page
document.addEventListener('DOMContentLoaded', function() {
    setupImageGallery();
    setupLazyLoading();
    setupPriceLoading();
});

/* ================= Autocomplete (mirrors list page) ================= */
(function initAutocomplete(){
    const input = document.getElementById('search-box');
    const list = document.getElementById('autocomplete-list');
    if(!input || !list) return;

    // Simulated product data (same style as list page)
    const products = Array.from({ length: 40 }, (_, i) => ({
        id: i + 1,
        title: `Monitor ${i + 1}`,
        price: `$${(Math.random()*300 + 50).toFixed(2)}`
    }));

    list.setAttribute('role','listbox');
    input.setAttribute('role','combobox');
    input.setAttribute('aria-autocomplete','list');
    input.setAttribute('aria-expanded','false');

    let currentIndex = -1;
    let currentSuggestions = [];

    function hide(){
        list.style.display='none';
        list.innerHTML='';
        currentIndex = -1;
        currentSuggestions = [];
        input.setAttribute('aria-expanded','false');
    }

    function navigateProduct(){
        // Same behavior: navigate to product page (self)
        window.location.href = 'index.html';
    }

    function build(value){
        const v = value.trim().toLowerCase();
        if(!v){ hide(); return; }
        currentSuggestions = products.filter(p=>p.title.toLowerCase().includes(v)).slice(0,8);
        if(!currentSuggestions.length){ hide(); return; }
        list.innerHTML = currentSuggestions.map((p,i)=>
            `<div class="autocomplete-item" role="option" aria-selected="${i===currentIndex}" data-index="${i}" data-id="${p.id}"><span>${p.title}</span><small>${p.price}</small></div>`
        ).join('');
        list.style.display='block';
        input.setAttribute('aria-expanded','true');
        currentIndex = -1;
    }

    function showDefault(){
        currentSuggestions = products.slice(0,8);
        list.innerHTML = currentSuggestions.map((p,i)=>
            `<div class="autocomplete-item" role="option" aria-selected="false" data-index="${i}" data-id="${p.id}"><span>${p.title}</span><small>${p.price}</small></div>`
        ).join('');
        list.style.display='block';
        input.setAttribute('aria-expanded','true');
        currentIndex = -1;
    }

    input.addEventListener('focus', ()=>{
        if(!input.value.trim()) showDefault(); else build(input.value);
    });

    input.addEventListener('input', e=>{
        if(!e.target.value.trim()) { showDefault(); return; }
        build(e.target.value);
    });

    input.addEventListener('keydown', e=>{
        const listVisible = list.style.display !== 'none';
        if(!listVisible && e.key === 'ArrowDown') showDefault();
        if(listVisible){
            const items = list.querySelectorAll('.autocomplete-item');
            if(e.key === 'ArrowDown'){
                e.preventDefault();
                currentIndex = (currentIndex + 1) % items.length;
                updateActive(items);
            } else if(e.key === 'ArrowUp') {
                e.preventDefault();
                currentIndex = (currentIndex - 1 + items.length) % items.length;
                updateActive(items);
            } else if(e.key === 'Enter') {
                e.preventDefault();
                navigateProduct();
            } else if(e.key === 'Escape') {
                hide();
            }
        } else if(e.key === 'Enter') {
            if(input.value.trim()){
                e.preventDefault();
                navigateProduct();
            }
        }
    });

    function updateActive(items){
        items.forEach((el,idx)=>{
            el.classList.toggle('active', idx===currentIndex);
            el.setAttribute('aria-selected', String(idx===currentIndex));
        });
    }

    list.addEventListener('click', e=>{
        const item = e.target.closest('.autocomplete-item');
        if(!item) return;
        navigateProduct();
    });

    document.addEventListener('click', e=>{
        if(!e.target.closest('.autocomplete-container')) hide();
    });

    const searchBtn = document.querySelector('.search-btn');
    if(searchBtn){
        searchBtn.addEventListener('click', ()=>{ navigateProduct(); });
    }
})();

// Image gallery functionality
function setupImageGallery() {
    const mainImage = document.querySelector('.main-image img');
    const thumbnails = document.querySelectorAll('.thumbnail-images img');

    thumbnails.forEach(thumb => {
        thumb.addEventListener('click', function() {
            // Remove active class from all thumbnails
            thumbnails.forEach(t => t.classList.remove('active'));
            // Add active class to clicked thumbnail
            this.classList.add('active');
            // Change main image
            mainImage.src = this.src.replace('w=60&h=60', 'w=500&h=500');
        });
    });
}

// Price loading functionality
function setupPriceLoading() {
    // Load price on first user interaction (any mouse / keyboard action)
    function loadPrice() {
        if (priceLoaded) return;
        priceLoaded = true;
        const priceSection = document.getElementById('priceSection');
        const loading = priceSection.querySelector('.price-loading');

        // Simulate loading delay
        setTimeout(() => {
            loading.style.display = 'none';

            // Create price content
            const priceContent = document.createElement('div');
            priceContent.className = 'price-content loaded';
            priceContent.innerHTML = `
                <div class="current-price">$199.99</div>
                <div class="price-details">
                    <span class="original-price">List Price: $299.99</span>
                    <span class="discount">Save $100.00 (33%)</span>
                </div>
                <div class="coupon-info">
                    <span style="color: #B12704; font-weight: bold;">Extra $10.00 off</span> when you apply this coupon
                </div>
            `;

            priceSection.appendChild(priceContent);
            removeInteractionListeners();
        }, 1500);
    }

    const interactionEvents = ['mousemove','mousedown','click','keydown','wheel','touchstart'];

    function onFirstInteraction() {
        loadPrice();
    }

    function removeInteractionListeners(){
        interactionEvents.forEach(evt => document.removeEventListener(evt, onFirstInteraction, listenerOpts));
    }

    const listenerOpts = { passive: true };
    interactionEvents.forEach(evt => document.addEventListener(evt, onFirstInteraction, listenerOpts));
}

// Lazy loading functionality
function setupLazyLoading() {
    const lazyElements = document.querySelectorAll('.lazy-section');

    const observerOptions = {
        root: null,
        rootMargin: '100px',
        threshold: 0.1
    };

    const observer = new IntersectionObserver((entries, observer) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                loadSectionContent(entry.target);
                observer.unobserve(entry.target);
            }
        });
    }, observerOptions);

    lazyElements.forEach(element => {
        observer.observe(element);
    });
}

function loadSectionContent(section) {
    const sectionType = section.dataset.section;
    const loading = section.querySelector('.section-loading');

    // Simulate loading delay
    setTimeout(() => {
        loading.classList.add('hidden');

        setTimeout(() => {
            loading.style.display = 'none';
            const content = createSectionContent(sectionType);
            section.appendChild(content);
        }, 300);
    }, 1000 + Math.random() * 1000); // Random delay between 1-2 seconds
}

function createSectionContent(sectionType) {
    const content = document.createElement('div');
    content.className = 'section-content loaded';

    switch (sectionType) {
        case 'reviews':
            content.innerHTML = `
                <div class="reviews-content">
                    <h2>Customer Reviews</h2>
                    <button class="write-review-btn">Write a customer review</button>
                    <div class="review-summary">
                        <div class="rating-breakdown">
                            <div class="overall-rating">
                                <span class="big-rating">4.2</span>
                                <div class="stars">★★★★☆</div>
                                <div>3,245 global ratings</div>
                            </div>
                        </div>
                        <div class="rating-bars">
                            <div class="rating-bar">
                                <span class="rating-label">5 star</span>
                                <div class="bar-container">
                                    <div class="bar-fill" data-width="45"></div>
                                </div>
                                <span class="rating-percentage">45%</span>
                            </div>
                            <div class="rating-bar">
                                <span class="rating-label">4 star</span>
                                <div class="bar-container">
                                    <div class="bar-fill" data-width="30"></div>
                                </div>
                                <span class="rating-percentage">30%</span>
                            </div>
                            <div class="rating-bar">
                                <span class="rating-label">3 star</span>
                                <div class="bar-container">
                                    <div class="bar-fill" data-width="15"></div>
                                </div>
                                <span class="rating-percentage">15%</span>
                            </div>
                            <div class="rating-bar">
                                <span class="rating-label">2 star</span>
                                <div class="bar-container">
                                    <div class="bar-fill" data-width="7"></div>
                                </div>
                                <span class="rating-percentage">7%</span>
                            </div>
                            <div class="rating-bar">
                                <span class="rating-label">1 star</span>
                                <div class="bar-container">
                                    <div class="bar-fill" data-width="3"></div>
                                </div>
                                <span class="rating-percentage">3%</span>
                            </div>
                        </div>
                    </div>

                    <div class="review-filters">
                        <div class="filter-buttons">
                            <button class="filter-btn active">All reviews</button>
                            <button class="filter-btn">5 stars</button>
                            <button class="filter-btn">4 stars</button>
                            <button class="filter-btn">3 stars</button>
                            <button class="filter-btn">With images</button>
                            <button class="filter-btn">Verified purchase</button>
                        </div>
                    </div>

                    <div class="reviews-list">
                        <div class="review-item">
                            <div class="review-header">
                                <div class="review-avatar">JD</div>
                                <div>
                                    <div class="reviewer-name">John D.</div>
                                    <div class="stars">★★★★★</div>
                                </div>
                                <span class="verified-purchase">Verified Purchase</span>
                                <span class="review-date">January 10, 2025</span>
                            </div>
                            <div class="review-title">Excellent tablet for the price</div>
                            <div class="review-content">Great build quality and performance. Battery life is impressive and the display is crisp. The 128GB storage is perfect for my needs, and the camera quality exceeded my expectations. Highly recommend for anyone looking for a budget-friendly tablet that doesn't compromise on features.</div>
                            <div class="review-images">
                                <img src="https://images.unsplash.com/photo-1544244015-0df4b3ffc6b0?w=80&h=80&fit=crop" alt="Review image 1" class="review-image">
                                <img src="https://images.unsplash.com/photo-1560472354-b33ff0c44a43?w=80&h=80&fit=crop" alt="Review image 2" class="review-image">
                            </div>
                            <div class="review-actions">
                                <span class="review-action">👍 Helpful (23)</span>
                                <span class="review-action">💬 Comment</span>
                                <span class="review-action">🚩 Report</span>
                            </div>
                            <div class="helpful-count">23 people found this helpful</div>
                        </div>

                        <div class="review-item">
                            <div class="review-header">
                                <div class="review-avatar">SM</div>
                                <div>
                                    <div class="reviewer-name">Sarah M.</div>
                                    <div class="stars">★★★★☆</div>
                                </div>
                                <span class="verified-purchase">Verified Purchase</span>
                                <span class="review-date">January 8, 2025</span>
                            </div>
                            <div class="review-title">Good value for money</div>
                            <div class="review-content">Works well for basic tasks like browsing, reading, and streaming. The screen is bright and clear. Camera quality could be better but overall satisfied with the purchase. Setup was easy and the tablet feels solid.</div>
                            <div class="review-actions">
                                <span class="review-action">👍 Helpful (15)</span>
                                <span class="review-action">💬 Comment</span>
                                <span class="review-action">🚩 Report</span>
                            </div>
                            <div class="helpful-count">15 people found this helpful</div>
                        </div>

                        <div class="review-item">
                            <div class="review-header">
                                <div class="review-avatar">MR</div>
                                <div>
                                    <div class="reviewer-name">Mike R.</div>
                                    <div class="stars">★★★★★</div>
                                </div>
                                <span class="verified-purchase">Verified Purchase</span>
                                <span class="review-date">January 5, 2025</span>
                            </div>
                            <div class="review-title">Perfect for work and entertainment</div>
                            <div class="review-content">I use this tablet for both work presentations and watching movies. The performance is smooth, battery lasts all day, and the build quality feels premium. The cellular connectivity is a great bonus for working on the go.</div>
                            <div class="review-actions">
                                <span class="review-action">👍 Helpful (31)</span>
                                <span class="review-action">💬 Comment</span>
                                <span class="review-action">🚩 Report</span>
                            </div>
                            <div class="helpful-count">31 people found this helpful</div>
                        </div>

                        <div class="review-item">
                            <div class="review-header">
                                <div class="review-avatar">LK</div>
                                <div>
                                    <div class="reviewer-name">Lisa K.</div>
                                    <div class="stars">★★★☆☆</div>
                                </div>
                                <span class="verified-purchase">Verified Purchase</span>
                                <span class="review-date">January 3, 2025</span>
                            </div>
                            <div class="review-title">Decent but has some issues</div>
                            <div class="review-content">The tablet works fine for basic use, but I've noticed some lag when running multiple apps. The screen is good but not as bright as I expected. For the price, it's okay but there might be better alternatives.</div>
                            <div class="review-actions">
                                <span class="review-action">👍 Helpful (8)</span>
                                <span class="review-action">💬 Comment</span>
                                <span class="review-action">🚩 Report</span>
                            </div>
                            <div class="helpful-count">8 people found this helpful</div>
                        </div>

                        <div class="review-item">
                            <div class="review-header">
                                <div class="review-avatar">TC</div>
                                <div>
                                    <div class="reviewer-name">Tom C.</div>
                                    <div class="stars">★★★★★</div>
                                </div>
                                <span class="verified-purchase">Verified Purchase</span>
                                <span class="review-date">December 28, 2024</span>
                            </div>
                            <div class="review-title">Great for students</div>
                            <div class="review-content">Bought this for my college courses and it's been perfect. Light enough to carry around campus, good battery life for all-day use, and handles all my apps without issues. The price point makes it accessible for students on a budget.</div>
                            <div class="review-images">
                                <img src="https://images.unsplash.com/photo-1586953208448-b95a79798f07?w=80&h=80&fit=crop" alt="Review image" class="review-image">
                            </div>
                            <div class="review-actions">
                                <span class="review-action">👍 Helpful (19)</span>
                                <span class="review-action">💬 Comment</span>
                                <span class="review-action">🚩 Report</span>
                            </div>
                            <div class="helpful-count">19 people found this helpful</div>
                        </div>
                    </div>

                    <div class="load-more-reviews">
                        <button class="load-more-btn">See more reviews</button>
                    </div>
                </div>
            `;

            // Animate rating bars after content loads
            setTimeout(() => {
                const bars = content.querySelectorAll('.bar-fill');
                bars.forEach(bar => {
                    const width = bar.dataset.width;
                    bar.style.width = width + '%';
                });
            }, 500);

            // Add filter functionality
            const filterBtns = content.querySelectorAll('.filter-btn');
            filterBtns.forEach(btn => {
                btn.addEventListener('click', function() {
                    filterBtns.forEach(b => b.classList.remove('active'));
                    this.classList.add('active');
                });
            });

            break;

        case 'related':
            content.innerHTML = `
                <div class="related-content">
                    <h2>Related Products</h2>
                    <div class="products-grid">
                        <a class="product-card" data-id="101" href="index.html?id=101">
                            <img src="https://images.unsplash.com/photo-1544244015-0df4b3ffc6b0?w=200&h=200&fit=crop" alt="Related Product 1">
                            <div class="product-info">
                                <h3>Premium Tablet Case</h3>
                                <div class="price">$29.99</div>
                                <div class="rating">★★★★☆ (1,234)</div>
                            </div>
                        </a>
                        <a class="product-card" data-id="102" href="index.html?id=102">
                            <img src="https://images.unsplash.com/photo-1560472354-b33ff0c44a43?w=200&h=200&fit=crop" alt="Related Product 2">
                            <div class="product-info">
                                <h3>Wireless Stylus Pen</h3>
                                <div class="price">$49.99</div>
                                <div class="rating">★★★★★ (856)</div>
                            </div>
                        </a>
                        <a class="product-card" data-id="103" href="index.html?id=103">
                            <img src="https://images.unsplash.com/photo-1586953208448-b95a79798f07?w=200&h=200&fit=crop" alt="Related Product 3">
                            <div class="product-info">
                                <h3>Portable Charger</h3>
                                <div class="price">$19.99</div>
                                <div class="rating">★★★★☆ (2,341)</div>
                            </div>
                        </a>
                        <a class="product-card" data-id="104" href="index.html?id=104">
                            <img src="https://images.unsplash.com/photo-1581091226825-a6a2a5aee158?w=200&h=200&fit=crop" alt="Related Product 4">
                            <div class="product-info">
                                <h3>Bluetooth Keyboard</h3>
                                <div class="price">$39.99</div>
                                <div class="rating">★★★★☆ (1,789)</div>
                            </div>
                        </a>
                    </div>
                </div>
            `;
            break;

        case 'frequently-bought':
            content.innerHTML = `
                <div class="frequently-bought-content">
                    <h2>Frequently bought together</h2>
                    <div class="bundle-container">
                        <div class="bundle-items">
                            <div class="bundle-item">
                                <img src="https://images.unsplash.com/photo-1544244015-0df4b3ffc6b0?w=100&h=100&fit=crop" alt="This item">
                                <div class="item-info">
                                    <div class="item-title">This item: Premium Tablet</div>
                                    <div class="item-price">$199.99</div>
                                </div>
                            </div>
                            <div class="plus">+</div>
                            <div class="bundle-item">
                                <img src="https://images.unsplash.com/photo-1560472354-b33ff0c44a43?w=100&h=100&fit=crop" alt="Case">
                                <div class="item-info">
                                    <div class="item-title">Premium Case</div>
                                    <div class="item-price">$29.99</div>
                                </div>
                            </div>
                        </div>
                        <div class="bundle-pricing">
                            <div class="total-price">Total price: <strong>$229.98</strong></div>
                            <button class="add-bundle-btn">Add both to Cart</button>
                        </div>
                    </div>
                </div>
            `;
            break;

        case 'compare':
            content.innerHTML = `
                <div class="compare-content">
                    <h2>Compare with similar items</h2>
                    <div class="compare-table">
                        <table>
                            <tr>
                                <th></th>
                                <th>This item</th>
                                <th>Alternative 1</th>
                                <th>Alternative 2</th>
                            </tr>
                            <tr>
                                <td>Price</td>
                                <td>$199.99</td>
                                <td>$249.99</td>
                                <td>$179.99</td>
                            </tr>
                            <tr>
                                <td>Screen Size</td>
                                <td>10.1"</td>
                                <td>11"</td>
                                <td>9.7"</td>
                            </tr>
                            <tr>
                                <td>Storage</td>
                                <td>128GB</td>
                                <td>256GB</td>
                                <td>64GB</td>
                            </tr>
                            <tr>
                                <td>Rating</td>
                                <td>★★★★☆</td>
                                <td>★★★★★</td>
                                <td>★★★☆☆</td>
                            </tr>
                        </table>
                    </div>
                </div>
            `;
            break;

        case 'description':
            content.innerHTML = `
                <div class="description-content">
                    <h2>Product Description</h2>
                    <div class="description-text">
                        <p>The Premium Tablet delivers exceptional performance and versatility in a sleek, portable design. With its stunning 10.1-inch Full HD display, you'll enjoy crystal-clear visuals whether you're streaming videos, browsing the web, or working on documents.</p>

                        <h3>Key Features:</h3>
                        <ul>
                            <li><strong>Powerful Performance:</strong> Octa-core processor ensures smooth multitasking and responsive performance</li>
                            <li><strong>Ample Storage:</strong> 128GB internal storage with expandable memory up to 1TB via microSD</li>
                            <li><strong>All-Day Battery:</strong> Up to 12 hours of mixed usage on a single charge</li>
                            <li><strong>Versatile Connectivity:</strong> Wi-Fi 6, Bluetooth 5.0, and optional cellular connectivity</li>
                            <li><strong>Premium Build:</strong> Sleek aluminum body with Gorilla Glass protection</li>
                        </ul>

                        <p>Perfect for students, professionals, and entertainment enthusiasts alike. The tablet comes with the latest Android operating system and access to millions of apps through the Google Play Store.</p>
                    </div>
                </div>
            `;
            break;
    }

    // Add CSS for the loaded content
    if (sectionType === 'related') {
        const style = document.createElement('style');
        style.textContent = `
            .products-grid {
                display: grid;
                grid-template-columns: repeat(4, 1fr);
                gap: 20px;
            }
            .product-card {
                border: 1px solid #ddd;
                border-radius: 4px;
                padding: 15px;
                text-align: center;
                transition: transform 0.2s, box-shadow 0.2s;
            }
            .product-card:hover {
                transform: translateY(-2px);
                box-shadow: 0 4px 12px rgba(0,0,0,0.1);
            }
            .product-card img {
                width: 100%;
                height: 150px;
                object-fit: cover;
                border-radius: 4px;
                margin-bottom: 10px;
            }
            .product-card h3 {
                font-size: 14px;
                margin-bottom: 5px;
            }
            .product-card .price {
                font-weight: bold;
                color: #B12704;
                margin-bottom: 5px;
            }
            .product-card .rating {
                font-size: 12px;
                color: #565959;
            }
        `;
        document.head.appendChild(style);
    }

    return content;
}

// Smooth scroll behavior for internal links
document.addEventListener('click', function(e) {
    if (e.target.matches('a[href^="#"]')) {
        e.preventDefault();
        const targetId = e.target.getAttribute('href');
        const targetElement = document.querySelector(targetId);
        if (targetElement) {
            targetElement.scrollIntoView({ behavior: 'smooth' });
        }
    }
});

// Add to cart functionality
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('add-to-cart-btn')) {
        e.target.textContent = 'Added to Cart!';
        e.target.style.backgroundColor = '#007600';
        setTimeout(() => {
            e.target.textContent = 'Add to Cart';
            e.target.style.backgroundColor = '#ffd814';
        }, 2000);
    }
});
