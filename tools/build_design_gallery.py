"""Build a self-contained UI kit from shared tokens and native Android QA renders."""
import base64
import html
import json
from io import BytesIO
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / "app/build/qa"
tokens = json.loads((ROOT / "design/tokens.json").read_text())


def image(name, crop=None):
    source = Image.open(QA / f"{name}.png")
    if crop:
        source = source.crop(crop)
    source.thumbnail((1000, 1400))
    out = BytesIO()
    source.save(out, format="PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(out.getvalue()).decode()


def row_image(name, color):
    source = Image.open(QA / f"{name}.png").convert("RGB")
    rgb = tuple(int(color[i:i+2], 16) for i in (1, 3, 5))
    column = [source.getpixel((source.width // 2, y)) == rgb for y in range(source.height)]
    runs = []
    start = 0
    for y in range(1, len(column) + 1):
        if y == len(column) or column[y] != column[start]:
            if column[start]:
                runs.append((start, y))
            start = y
    top, bottom = max(runs, key=lambda pair: pair[1] - pair[0])
    density = source.width / 412
    return image(name, (round(20*density), top, round(source.width-20*density), bottom))


css_vars = ";".join(f"--{key.replace('_', '-')}: {value}" for key, value in tokens['color'].items())
swatches = "".join(f'<div class="swatch"><i style="background:{tokens["color"][key]}"></i>'
                   f'<span>{label}</span><code>{tokens["color"][key]}</code></div>'
                   for key, label in [('primary', '操作'), ('primary_container', '選択'), ('ink', '文字'),
                                      ('muted', '補足'), ('canvas', '背景'), ('surface', '面'),
                                      ('outline', '境界'), ('danger', '停止')])
type_rows = "".join(f'<div class="type-row"><span style="font-size:{size}px;'
                    f'font-weight:{500 if key in ["title", "station", "label"] else 400}">{text}</span>'
                    f'<code>{size} sp</code></div>' for key, size, text in [
                        ('title', 26, 'のりおり'), ('station', 22, '池袋駅'),
                        ('body', 16, 'ここに入ったら通知'), ('label', 14, '保存'), ('caption', 12, '山手線')])
states = "".join(f'<figure class="state"><img src="{row_image(name, tokens["color"][key])}" '
                 f'alt="{label}の駅の行"><figcaption>{label}</figcaption></figure>'
                 for name, key, label in [('default-home', 'surface', 'オフ'),
                                           ('home-protected', 'primary_container', 'オン'),
                                           ('home-alarm', 'danger_container', '振動中')])
screens = "".join(f'<figure class="screen"><button class="image-button" aria-label="{label}を拡大" '
                  f'data-image="{name}"><img src="{image(name)}" alt="{label}"></button>'
                  f'<figcaption>{label}</figcaption></figure>' for name, label in [
                      ('editor-ikebukuro', '通知する駅'), ('default-on-areas', 'オンにする場所'),
                      ('station-candidates', '駅の追加'), ('last-train-phone', '終電'),
                      ('last-train-empty', '終電の登録'), ('home-station-picker', '家の最寄り駅'), ('notification-area-editor', '通知するエリア'),
                      ('last-train-error', '取得できない')])
icons = "".join(f'<button class="icon-button" aria-label="{label}"><svg viewBox="0 0 24 24" '
                f'aria-hidden="true"><path d="{path}"/></svg></button>' for label, path in [
                    ('追加', 'M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z'),
                    ('閉じる', 'm6.4 5 5.6 5.6L17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z'),
                    ('削除', 'M9 3h6l1 2h4v2H4V5h4zM6 9h2v10h8V9h2v10c0 1.1-.9 2-2 2H8c-1.1 0-2-.9-2-2zM10 9h1.5v8H10zM13 9h1.5v8H13z'),
                    ('編集', 'm9 6-1.4 1.4 4.6 4.6-4.6 4.6L9 18l6-6z')])
document = r'''<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>のりおり · Design system</title>
<style>
:root{@@VARS@@;font-family:"Noto Sans JP","Noto Sans CJK JP",system-ui,sans-serif;color:var(--ink);background:var(--canvas);font-synthesis:none}
*{box-sizing:border-box}body{margin:0}button,input{font:inherit}button{cursor:pointer}button:focus-visible,a:focus-visible,input:focus-visible{outline:3px solid var(--primary);outline-offset:4px}
a{color:var(--on-primary-container);text-underline-offset:4px}main{max-width:1080px;margin:auto;padding:48px 32px 64px}header{display:flex;justify-content:space-between;align-items:center;gap:24px;padding-bottom:32px;border-bottom:1px solid var(--outline)}h1{font-size:26px;font-weight:600;margin:0;letter-spacing:-.04em}.kicker{font-size:12px;color:var(--muted);margin-top:8px;letter-spacing:.08em}nav{display:flex;gap:24px;font-size:14px}nav a{text-decoration:none;color:var(--muted)}nav a:hover{color:var(--primary)}section{margin-top:48px}h2{font-size:14px;font-weight:600;margin:0 0 20px;display:flex;align-items:center;gap:12px}h2 small{font:12px ui-monospace,monospace;color:var(--muted)}h3{font-size:14px;font-weight:500;margin:0 0 20px}.foundations{display:grid;grid-template-columns:1fr 1fr;gap:20px}.panel{padding:24px;background:var(--surface);border-radius:28px}.swatches{display:grid;grid-template-columns:repeat(4,1fr);gap:20px 12px}.swatch i{display:block;height:48px;border-radius:12px;border:1px solid #17212f12}.swatch span{display:block;margin-top:8px;font-size:12px}.swatch code{font:10px ui-monospace,monospace;color:var(--muted)}.type-row{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px}.type-row:last-child{margin:0}.type-row code{font:11px ui-monospace,monospace;color:var(--muted);white-space:nowrap}.metrics{grid-column:1/-1;display:flex;align-items:center;justify-content:space-between;gap:20px;font-size:12px;color:var(--muted)}.space-samples{display:flex;gap:12px;align-items:flex-end;height:40px}.space-samples i{display:block;background:var(--primary-container);width:24px;border-bottom:2px solid var(--primary)}.space-samples span{text-align:center;font:10px ui-monospace,monospace;color:var(--muted)}.radius-samples{display:flex;gap:16px}.radius-samples i{width:48px;height:48px;border:1px solid var(--outline);display:grid;place-items:center;font:11px ui-monospace,monospace;color:var(--muted);font-style:normal}.components{display:grid;grid-template-columns:1.1fr 1fr;gap:20px}.states{display:grid;gap:16px}.state{margin:0}.state img{width:100%;height:auto;display:block}.state figcaption{font-size:11px;color:var(--muted);padding:8px 4px}.specimen{margin-bottom:28px}.specimen:last-child{margin:0}.button-row{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.action{border:0;border-radius:999px;min-height:52px;padding:12px 24px;font-size:14px;font-weight:500;background:var(--primary-container);color:var(--on-primary-container);transition:filter 160ms,transform 160ms}.action.primary{background:var(--primary);color:white}.action.danger{background:var(--danger);color:white}.action:disabled{background:var(--disabled);color:var(--disabled-ink);cursor:default}.action:not(:disabled):hover{filter:brightness(.96)}.action:not(:disabled):active{transform:scale(.97)}.chip-row{display:flex;gap:8px;flex-wrap:wrap}.chip{min-height:48px;border:0;border-radius:999px;background:var(--container);color:var(--muted);padding:10px 16px;font-size:14px}.chip[aria-pressed=true]{background:var(--primary-container);color:var(--on-primary-container)}.chip[aria-pressed=true]:after{content:'×';margin-left:8px}.icon-button{width:48px;height:48px;padding:12px;border:0;border-radius:999px;background:transparent;color:var(--ink)}.icon-button:hover{background:var(--container)}svg{width:24px;height:24px;fill:currentColor}.range{width:100%;accent-color:var(--primary);margin:0;height:48px}.range-labels{display:flex;justify-content:space-between;color:var(--muted);font-size:12px}.screens{display:grid;grid-template-columns:repeat(3,1fr);gap:24px}.screen{margin:0}.image-button{padding:0;border:1px solid var(--outline);border-radius:24px;overflow:hidden;background:var(--surface);width:100%;display:block}.image-button img{width:100%;display:block}.screen figcaption{font-size:12px;margin:12px 0 0;color:var(--muted)}.review-note{font-size:12px;color:var(--muted);margin-top:16px}.adaptive{display:grid;grid-template-columns:2fr 1fr;gap:24px;margin-top:28px;align-items:start}.wide img{aspect-ratio:840/740;object-fit:cover}.zoomed-text img{max-height:360px;object-fit:cover;object-position:bottom}details{margin-top:32px;padding-top:24px;border-top:1px solid var(--outline);font-size:12px;color:var(--muted)}summary{cursor:pointer}details p{line-height:2;margin-bottom:0}dialog{padding:12px;border:0;border-radius:24px;max-width:90vw;max-height:92vh;background:var(--surface)}dialog::backdrop{background:#17212f70}dialog img{display:block;max-height:82vh;max-width:84vw;margin:auto;object-fit:contain}dialog .dismiss{display:block;margin-left:auto;margin-bottom:8px}footer{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-top:32px;font-size:11px;color:var(--muted)}code{font-family:ui-monospace,monospace}.flash{box-shadow:0 0 0 3px var(--primary-container)}
@media(max-width:720px){main{padding:28px 20px}header{align-items:start;flex-direction:column;gap:20px}nav{gap:28px}.foundations,.components{grid-template-columns:1fr}.metrics{flex-wrap:wrap}.screens{grid-template-columns:1fr 1fr;gap:16px}.adaptive{grid-template-columns:1fr}.zoomed-text{max-width:50%;margin:auto}h1{font-size:24px}.swatches{gap:16px 10px}.panel{padding:20px}}
@media(prefers-reduced-motion:reduce){*,*:before,*:after{transition:none!important;animation:none!important;scroll-behavior:auto!important}}
</style></head><body><main>
<header><div><h1>のりおり</h1><div class="kicker">DESIGN SYSTEM</div></div><nav aria-label="見本の目次"><a href="#foundations">基本</a><a href="#components">部品</a><a href="#screens">画面</a></nav></header>
<section id="foundations"><h2><small>01</small> 基本</h2><div class="foundations"><div class="panel"><h3>色</h3><div class="swatches">@@SWATCHES@@</div></div><div class="panel"><h3>文字</h3>@@TYPE@@</div><div class="panel metrics"><div><h3>余白</h3><div class="space-samples"><span><i style="height:4px"></i>4</span><span><i style="height:8px"></i>8</span><span><i style="height:12px"></i>12</span><span><i style="height:16px"></i>16</span><span><i style="height:24px"></i>24</span><span><i style="height:32px"></i>32</span></div></div><div><h3>角丸</h3><div class="radius-samples"><i style="border-radius:12px">12</i><i style="border-radius:20px">20</i><i style="border-radius:28px">28</i></div></div><span>操作 ≥ 48 dp<br><br>動き 160 / 240 ms</span></div></div></section>
<section id="components"><h2><small>02</small> 部品</h2><div class="components"><div class="panel"><h3>駅の行</h3><div class="states">@@STATES@@</div></div><div class="panel"><div class="specimen"><h3>操作</h3><div class="button-row"><button class="action primary feedback">保存</button><button class="action feedback">＋ 追加</button><button class="action danger feedback">停止</button><button class="action" disabled>保存</button></div></div><div class="specimen"><h3>選択</h3><div class="chip-row" role="group" aria-label="場所の選択"><button class="chip" aria-pressed="true">目白駅</button><button class="chip" aria-pressed="false">大塚駅</button><button class="chip" aria-pressed="false">十条駅</button></div></div><div class="specimen"><h3>アイコン</h3><div class="button-row">@@ICONS@@</div></div><div class="specimen"><h3>円の大きさ</h3><input class="range" type="range" min="0" max="1000" value="280" aria-label="円の大きさの見本"><div class="range-labels"><span>近く</span><span>広く</span></div></div></div></div></section>
<section id="screens"><h2><small>03</small> 画面</h2><div class="screens">@@SCREENS@@</div><p class="review-note">Androidの描画。終電の地図は実タイルの検証用画像。</p><div class="adaptive"><figure class="screen wide"><button class="image-button" data-image="editor-unfolded" aria-label="幅840dpの編集画面を拡大"><img src="@@WIDE@@" alt="幅840dpの地図と操作欄"></button><figcaption>幅 840 dp</figcaption></figure><figure class="screen zoomed-text"><button class="image-button" data-image="editor-large-text" aria-label="文字1.3倍の編集画面を拡大"><img src="@@LARGE@@" alt="文字を1.3倍にした編集画面"></button><figcaption>文字 1.3倍</figcaption></figure></div></section>
<details><summary>参照と実装</summary><p><a href="https://play.google.com/store/apps/details?id=com.google.android.deskclock">Google Clock</a> · <a href="https://assets.st-note.com/production/uploads/images/208118565/picture_pc_beffe0fac2a842566cfce620da5314fb.png?width=1200">Google Maps</a> · <a href="https://developer.android.com/guide/topics/ui/accessibility/views/apps-views">Android Accessibility</a><br>実画像を見て、行末スイッチ・選択面・検索候補の整列を参照。<br><code>tokens.json</code> → <code>DesignTokens.java</code> / <code>colors.xml</code> → <code>DesignSystem.java</code></p></details>
<footer><span>初期値：池袋駅</span><span>目白 · 大塚 · 十条 · 大山 · 椎名町でオン</span></footer>
</main><dialog id="zoom"><button class="icon-button dismiss" aria-label="閉じる"><svg viewBox="0 0 24 24"><path d="m6.4 5 5.6 5.6L17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z"/></svg></button><img alt="拡大したAndroid画面"></dialog>
<script>
const zoom=document.getElementById('zoom');
document.querySelectorAll('[data-image]').forEach(button=>button.addEventListener('click',()=>{zoom.querySelector('img').src=button.querySelector('img').src;zoom.querySelector('img').alt=button.querySelector('img').alt;zoom.showModal()}));
zoom.querySelector('.dismiss').addEventListener('click',()=>zoom.close());
zoom.addEventListener('click',event=>{if(event.target===zoom)zoom.close()});
document.querySelectorAll('.chip').forEach(button=>button.addEventListener('click',()=>{if(button.getAttribute('aria-pressed')==='true'){button.remove();return}document.querySelectorAll('.chip').forEach(chip=>chip.setAttribute('aria-pressed',String(chip===button)))}));
document.querySelectorAll('.feedback').forEach(button=>button.addEventListener('click',()=>{button.classList.add('flash');setTimeout(()=>button.classList.remove('flash'),240)}));
</script></body></html>'''
replacements = {'VARS': css_vars, 'SWATCHES': swatches, 'TYPE': type_rows, 'STATES': states,
                'SCREENS': screens, 'ICONS': icons, 'WIDE': image('editor-unfolded'),
                'LARGE': image('editor-large-text')}
for key, value in replacements.items():
    document = document.replace('@@' + key + '@@', value)
document = document.replace('<section id="foundations">',
    '<section aria-label="アプリアイコン"><h2>アイコン</h2>'
    '<div class="panel"><img style="display:block;width:100%;max-width:768px;margin:auto" '
    f'src="{image("app-icon-masks")}" alt="のりおりのアイコン：丸型・角丸・テーマ色">'
    '<p class="review-note">丸型 · 角丸 · テーマ色</p></div></section><section id="foundations">')
target = ROOT / 'design/index.html'
target.write_text(document)
print(f'{target} ({target.stat().st_size} bytes)')
