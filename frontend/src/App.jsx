import { useEffect, useRef, useState } from 'react';

const emptyStats = { indexedFiles: [], totalChunks: 0, totalQuestions: 0 };

function friendlyError(message) {
  const raw = String(message || 'The request could not be completed.');
  if (/429|RESOURCE_EXHAUSTED|quota exceeded|exceeded your current quota/i.test(raw)) {
    return /embed_content|embedding/i.test(raw)
      ? 'Google AI embedding rate limit reached (100 req/min on free tier). Please wait a moment and try indexing again.'
      : 'Google AI request rate limit reached. Please wait a moment and try again.';
  }
  return raw.length > 240 ? `${raw.slice(0, 237)}...` : raw;
}

async function readResponse(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || data.message || `Request failed (${response.status})`);
  return data;
}

export default function App() {
  const [stats, setStats] = useState(emptyStats);
  const [selectedFiles, setSelectedFiles] = useState([]);
  const [messages, setMessages] = useState([]);
  const [query, setQuery] = useState('');
  const [uploading, setUploading] = useState(false);
  const [asking, setAsking] = useState(false);
  const [notice, setNotice] = useState('');
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef(null);

  useEffect(() => {
    fetch('/api/status')
      .then(readResponse)
      .then(setStats)
      .catch((error) => setNotice(friendlyError(error.message)));
  }, []);

  function chooseFiles(files) {
    const pdfs = Array.from(files).filter((file) => file.name.toLowerCase().endsWith('.pdf'));
    setSelectedFiles(pdfs);
    setNotice(files.length && !pdfs.length ? 'Only PDF files can be added.' : '');
  }

  async function upload(event) {
    event.preventDefault();
    if (!selectedFiles.length || uploading) return;
    setUploading(true);
    setNotice('');
    const body = new FormData();
    selectedFiles.forEach((file) => body.append('files', file));
    try {
      await readResponse(await fetch('/api/upload', { method: 'POST', body }));
      const status = await readResponse(await fetch('/api/status'));
      setStats(status);
      setSelectedFiles([]);
      if (fileInput.current) fileInput.current.value = '';
      setNotice('Documents indexed successfully.');
    } catch (error) {
      setNotice(friendlyError(error.message));
    } finally {
      setUploading(false);
    }
  }

  async function ask(event) {
    event.preventDefault();
    const question = query.trim();
    if (!question || asking) return;
    setMessages((current) => [...current, { question }]);
    setQuery('');
    setAsking(true);
    setNotice('');
    try {
      const data = await readResponse(await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: question })
      }));
      setMessages((current) => [...current, { answer: data.answer, source: data.source }]);
      setStats((current) => ({ ...current, totalQuestions: current.totalQuestions + 1 }));
    } catch (error) {
      setNotice(friendlyError(error.message));
    } finally {
      setAsking(false);
    }
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark">f.</span><span><strong>folio</strong><small>Your research, in context</small></span></div>
        <section aria-labelledby="upload-title">
          <div className="section-label"><span id="upload-title">ADD TO YOUR LIBRARY</span><b>{stats.indexedFiles.length} files</b></div>
          <form onSubmit={upload}>
            <label className={`drop-zone ${dragging ? 'is-dragging' : ''}`} onDragEnter={(event) => { event.preventDefault(); setDragging(true); }} onDragOver={(event) => event.preventDefault()} onDragLeave={() => setDragging(false)} onDrop={(event) => { event.preventDefault(); setDragging(false); chooseFiles(event.dataTransfer.files); }}>
              <span className="upload-symbol">+</span><span><strong>Choose PDFs or drop them here</strong><small>PDF format · up to 200 MB per upload</small></span>
              <input ref={fileInput} type="file" multiple accept="application/pdf,.pdf" onChange={(event) => chooseFiles(event.target.files)} />
            </label>
            {selectedFiles.length > 0 && <div className="selected-files">{selectedFiles.map((file) => <span key={file.name}>{file.name}</span>)}</div>}
            <button className="primary-button" type="submit" disabled={!selectedFiles.length || uploading}>{uploading ? 'Indexing documents...' : 'Index documents'}</button>
            {notice && <p className="notice" role="status">{notice}</p>}
          </form>
        </section>
        <section className="documents" aria-labelledby="documents-title">
          <div className="section-label"><span id="documents-title">YOUR DOCUMENTS</span></div>
          {stats.indexedFiles.length ? <div className="document-list">{stats.indexedFiles.map((file) => <div className="document-row" key={file}><span className="document-icon">PDF</span><span>{file}</span></div>)}</div> : <p className="empty-documents">Your library is empty. Add a PDF to start asking questions.</p>}
        </section>
        <footer className="stats"><div><strong>{stats.totalChunks}</strong><small>Text segments</small></div><div><strong>{stats.totalQuestions}</strong><small>Questions</small></div></footer>
      </aside>
      <main className="main">
        <header className="topline"><span>Workspace <i>/</i> <b>Ask your library</b></span><span className="connection"><em /> PRIVATE WORKSPACE</span></header>
        <section className="workspace">
          <div className="intro"><p className="eyebrow">A clearer way to work through your reading</p><h1>Your documents,<br />in conversation.</h1><p>Ask a question about your PDFs. Folio finds relevant passages and gives you an answer with its source.</p></div>
          <div className="conversation" aria-live="polite">
            {!messages.length && <div className="welcome"><strong>Start with a question</strong><p>Try “What are the main findings?” or “Summarize the key arguments in this paper.” Answers are grounded in your documents.</p></div>}
            {messages.map((message, index) => message.question ? <div className="turn" key={`${message.question}-${index}`}><div className="question">{message.question}</div>{messages[index + 1]?.answer && <><div className="answer"><span className="answer-label">Folio answer</span><p>{messages[index + 1].answer}</p></div><span className="source">⌁ {messages[index + 1].source || 'Unknown.pdf'}</span></>}</div> : null)}
            {asking && <div className="loading"><span /><span /><span /> Searching your documents...</div>}
          </div>
          <div className="composer-wrap"><form className="composer" onSubmit={ask}><textarea value={query} onChange={(event) => setQuery(event.target.value)} rows="1" placeholder="Ask something about your documents..." aria-label="Question about your documents" /><button className="send-button" type="submit" disabled={asking || !query.trim()} aria-label="Send question">↑</button></form><p className="composer-note">ANSWERS ARE GENERATED FROM YOUR INDEXED DOCUMENTS</p></div>
        </section>
      </main>
    </div>
  );
}
