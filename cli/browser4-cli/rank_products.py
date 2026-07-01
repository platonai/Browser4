import json, re, sys

with open(sys.argv[1], 'r', encoding='utf-8') as f:
    content = f.read()

# Skip cargo build output lines and find JSON array
lines = content.split('\n')
json_start = None
for i, line in enumerate(lines):
    if line.strip().startswith('['):
        json_start = i
        break

json_text = '\n'.join(lines[json_start:])
data = json.loads(json_text)

unsuitable = [
    'daughter', 'girlfriend', 'wife', 'mom ', 'mother', 'her ',
    'blank', 'engraver', 'trophy', 'plaque', 'definition',
    'christian', 'jesus', 'bible', 'religious', 'guardian angel',
    'difference maker', 'graduation', 'nurse', 'teacher', 'boss',
    'employee', 'coworker', 'appreciation', 'thank you',
    'romantic', 'valentine', 'love you', 'eternal love',
    'butterfly', 'dancer', 'panda', 'lily flower',
    'memorial', 'sympathy',
]

boy_themes = {
    'space': ['solar system', 'saturn', 'galaxy', 'moon', 'astronomy', 'planet', 'nebula', 'cosmic'],
    'science': ['dna', 'science', 'biology', 'laboratory'],
    'animal_cool': ['eagle', 'wolf', 'wolves', 'dragon', 'lion', 'shark', 'scorpion'],
    'tech': ['airplane', 'plane', 'aviation'],
    'animal_fun': ['dolphin', 'turtle', 'owl', 'axolotl', 'elephant', 'giraffe', 'bird', 'sea turtle', 'penguin'],
    'nature': ['tree of life', 'lightning', 'mountain', 'ocean', 'cloud'],
    'fun': ['night light', 'led base', 'colorful', 'multicolor'],
}

suitable = []
for item in data:
    t = item['title'].lower()
    if any(kw in t for kw in unsuitable):
        continue
    if ('blank' in t and 'block' in t) or ('blank' in t and 'crystal' in t):
        continue
    if 'for engraving' in t or 'for 2d' in t or 'for 3d' in t:
        continue

    rmatch = re.search(r'([\d.]+)\s*out of', item['rating'])
    rating = float(rmatch.group(1)) if rmatch else 0
    pmatch = re.search(r'\$([\d,]+\.?\d*)', item['price'])
    price = float(pmatch.group(1).replace(',', '')) if pmatch else 0

    score = rating * 10 + min(price, 50) * 0.1
    for theme, keywords in boy_themes.items():
        for kw in keywords:
            if kw in t:
                score += {'space': 30, 'science': 25, 'animal_cool': 20, 'tech': 15, 'animal_fun': 10, 'nature': 5, 'fun': 8}[theme]
                break

    item['score'] = score
    item['rating_num'] = rating
    item['price_num'] = price
    suitable.append(item)

suitable.sort(key=lambda x: x['score'], reverse=True)

print(f'Total: {len(data)}, Suitable: {len(suitable)}')
print()
for i, item in enumerate(suitable[:15], 1):
    print(f'{i}. [{item["rating_num"]:.1f}★ | ${item["price_num"]:.2f} | score={item["score"]:.0f}]')
    print(f'   {item["title"][:120]}')
    asin = item['asin']
    clean_url = f'https://www.amazon.com/dp/{asin}'
    print(f'   ASIN: {asin} | URL: {clean_url}')
    print()
