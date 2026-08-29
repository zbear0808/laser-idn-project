import { useState, useEffect, useRef } from 'react';
import './App.css';

function App() {
  const [status, setStatus] = useState('Disconnected');
  const [latency, setLatency] = useState<number | null>(null);
  const ws = useRef<WebSocket | null>(null);

  useEffect(() => {
    // Connect to Control Socket
    ws.current = new WebSocket('ws://localhost:8080');

    ws.current.onopen = () => {
      setStatus('Connected');
    };

    ws.current.onclose = () => {
      setStatus('Disconnected');
    };

    ws.current.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'pong') {
          const rtt = performance.now() - data.timestamp;
          setLatency(rtt);
        }
      } catch (e) {
        console.error('Failed to parse message', e);
      }
    };

    return () => {
      ws.current?.close();
    };
  }, []);

  const sendPing = () => {
    if (ws.current?.readyState === WebSocket.OPEN) {
      ws.current.send(JSON.stringify({
        type: 'ping',
        timestamp: performance.now()
      }));
    }
  };

  return (
    <div className="App">
      <h1>Laser Engine Control</h1>
      <p>Status: <strong>{status}</strong></p>
      
      <div className="card">
        <button onClick={sendPing} disabled={status !== 'Connected'}>
          Send Ping
        </button>
        {latency !== null && (
          <p>Round-trip Latency: <strong>{latency.toFixed(2)} ms</strong></p>
        )}
      </div>
    </div>
  );
}

export default App;
