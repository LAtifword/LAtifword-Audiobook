from pathlib import Path
import re, time, json

SOURCE = Path('/home/ubuntu/upload/أنا_المنعزل_النسخة_النهائية_محمد_لطيف-1.txt')
text = re.sub(r'\s+', ' ', SOURCE.read_text(encoding='utf-8').replace('\ufeff', '').strip())

def split_for_narration(value, max_chars):
    sentences = [s.strip() for s in re.split(r'(?<=[.!?؟؛…:])\s+', value) if s.strip()]
    out, current = [], []
    def flush():
        if current:
            out.append(' '.join(current).strip())
            current.clear()
    for sentence in sentences:
        if len(sentence) > max_chars:
            flush(); start = 0
            while start < len(sentence):
                end = min(start + max_chars, len(sentence))
                if end < len(sentence):
                    space = sentence.rfind(' ', start, end)
                    if space > start + max_chars // 2: end = space
                out.append(sentence[start:end].strip())
                start = end
                while start < len(sentence) and sentence[start].isspace(): start += 1
        elif not current:
            current.append(sentence)
        elif sum(map(len, current)) + len(current) + len(sentence) <= max_chars:
            current.append(sentence)
        else:
            flush(); current.append(sentence)
    flush(); return out

def measure(name, chars, steps):
    t0 = time.perf_counter(); chunks = split_for_narration(text, chars); split_ms=(time.perf_counter()-t0)*1000
    total_chunk_chars=sum(map(len,chunks))
    return {
        'name': name, 'chunk_target_chars': chars, 'nfe_steps': steps,
        'sections': len(chunks), 'source_chars': len(text),
        'chunk_chars': total_chunk_chars,
        'estimated_refinement_steps': len(chunks)*steps,
        'split_runtime_ms': round(split_ms,3),
        'avg_chars_per_section': round(total_chunk_chars/len(chunks),1),
    }

old=measure('old',220,32)
new=measure('optimized',560,24)
result={'source':str(SOURCE),'source_chars':len(text),'source_words':len(text.split()),'old':old,'optimized':new,
        'section_reduction_pct':round((1-new['sections']/old['sections'])*100,1),
        'estimated_refinement_work_reduction_pct':round((1-new['estimated_refinement_steps']/old['estimated_refinement_steps'])*100,1),
        'relative_work_ratio':round(new['estimated_refinement_steps']/old['estimated_refinement_steps'],3)}
Path('/home/ubuntu/LAtifword-Audiobook/benchmark-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
