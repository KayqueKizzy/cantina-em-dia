import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CantinaApp {
    private static final String ETCD = System.getenv().getOrDefault("ETCD_URL", "http://127.0.0.1:2379");
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final Pattern KV_PATTERN = Pattern.compile("\\{\\s*\\\"key\\\"\\s*:\\s*\\\"([^\\\"]*)\\\".*?\\\"value\\\"\\s*:\\s*\\\"([^\\\"]*)\\\".*?}", Pattern.DOTALL);

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/", CantinaApp::handle);
        server.start();
        System.out.println("Cantina em Dia disponível em http://localhost:8080");
        System.out.println("etcd: " + ETCD);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                send(exchange, 200, "text/html; charset=utf-8", page());
                return;
            }
            if (path.startsWith("/api/")) {
                api(exchange, path);
                return;
            }
            send(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"Página não encontrada\"}");
        } catch (Exception e) {
            send(exchange, 500, "application/json; charset=utf-8", jsonError(e.getMessage()));
        }
    }

    private static void api(HttpExchange x, String path) throws Exception {
        String method = x.getRequestMethod();
        String query = x.getRequestURI().getRawQuery();
        if (path.equals("/api/menu")) {
            String date = queryValue(query, "date");
            if (method.equals("GET")) {
                sendJson(x, 200, list("/cantina/menu/" + date + "/"));
                return;
            }
            if (method.equals("POST")) {
                String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String id = UUID.randomUUID().toString();
                String record = withField(body, "id", id);
                String recordDate = field(record, "date");
                put("/cantina/menu/" + recordDate + "/" + id, record);
                sendJson(x, 201, record);
                return;
            }
        }
        if (path.startsWith("/api/menu/")) {
            String id = path.substring("/api/menu/".length());
            String date = queryValue(query, "date");
            String key = "/cantina/menu/" + date + "/" + id;
            if (method.equals("PUT")) {
                String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String record = withField(body, "id", id);
                put(key, record);
                sendJson(x, 200, record);
                return;
            }
            if (method.equals("DELETE")) {
                delete(key);
                sendJson(x, 200, "{\"deleted\":true}");
                return;
            }
        }
        if (path.equals("/api/reviews")) {
            String date = queryValue(query, "date");
            if (method.equals("GET")) {
                sendJson(x, 200, list("/cantina/reviews/" + date + "/"));
                return;
            }
            if (method.equals("POST")) {
                String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String id = UUID.randomUUID().toString();
                String record = withField(body, "id", id);
                String recordDate = field(record, "date");
                String dishId = field(record, "dishId");
                put("/cantina/reviews/" + recordDate + "/" + dishId + "/" + id, record);
                sendJson(x, 201, record);
                return;
            }
        }
        if (path.startsWith("/api/reviews/")) {
            String id = path.substring("/api/reviews/".length());
            String date = queryValue(query, "date");
            String dishId = queryValue(query, "dishId");
            String key = "/cantina/reviews/" + date + "/" + dishId + "/" + id;
            if (method.equals("PUT")) {
                String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String record = withField(body, "id", id);
                put(key, record);
                sendJson(x, 200, record);
                return;
            }
            if (method.equals("DELETE")) {
                delete(key);
                sendJson(x, 200, "{\"deleted\":true}");
                return;
            }
        }
        sendJson(x, 404, "{\"error\":\"Operação não encontrada\"}");
    }

    private static void put(String key, String value) throws Exception {
        String payload = "{\"key\":" + quote(b64(key)) + ",\"value\":" + quote(b64(value)) + "}";
        etcd("/v3/kv/put", payload);
    }

    private static void delete(String key) throws Exception {
        etcd("/v3/kv/deleterange", "{\"key\":" + quote(b64(key)) + "}");
    }

    private static List<String> list(String prefix) throws Exception {
        String end = prefixEnd(prefix);
        String payload = "{\"key\":" + quote(b64(prefix)) + ",\"range_end\":" + quote(b64(end)) + "}";
        String response = etcd("/v3/kv/range", payload);
        List<String> result = new ArrayList<>();
        Matcher matcher = KV_PATTERN.matcher(response);
        while (matcher.find()) {
            String value = new String(Base64.getDecoder().decode(matcher.group(2)), StandardCharsets.UTF_8);
            result.add(value);
        }
        return result;
    }

    private static String etcd(String endpoint, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ETCD + endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) throw new IOException("etcd respondeu " + response.statusCode() + ": " + response.body());
        return response.body();
    }

    private static String prefixEnd(String prefix) {
        byte[] bytes = prefix.getBytes(StandardCharsets.UTF_8);
        for (int i = bytes.length - 1; i >= 0; i--) {
            int value = bytes[i] & 0xff;
            if (value != 0xff) {
                bytes[i] = (byte) (value + 1);
                return new String(bytes, 0, i + 1, StandardCharsets.UTF_8);
            }
        }
        return "\\0";
    }

    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String queryValue(String query, String name) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        return "";
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Campo obrigatório ausente: " + name);
        return m.group(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

    private static String withField(String json, String name, String value) {
        String clean = json.trim();
        String addition = quote(name) + ":" + quote(value);
        if (clean.equals("{}")) return "{" + addition + "}";
        return "{" + addition + "," + clean.substring(1, clean.length() - 1) + "}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String jsonError(String message) { return "{\"error\":" + quote(message == null ? "Erro inesperado" : message) + "}"; }
    private static void sendJson(HttpExchange x, int status, Object value) throws IOException {
        String body;
        if (value instanceof List<?> list) body = "[" + String.join(",", list.stream().map(Object::toString).toList()) + "]";
        else body = value.toString();
        send(x, status, "application/json; charset=utf-8", body);
    }
    private static void send(HttpExchange x, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        x.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        x.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        if (x.getRequestMethod().equals("OPTIONS")) { x.sendResponseHeaders(204, -1); return; }
        x.sendResponseHeaders(status, bytes.length);
        try (var out = x.getResponseBody()) { out.write(bytes); }
    }

    private static String page() {
        return """
                <!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Cantina em Dia</title>
                <style>
                :root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#18231f;background:#f5f7f2}*{box-sizing:border-box}body{margin:0}.top{background:#123d32;color:white;padding:28px max(calc((100vw - 1100px)/2),24px)}.top small{color:#b9d7c8;letter-spacing:.12em;text-transform:uppercase}.top h1{font-size:32px;margin:8px 0}.top p{margin:0;color:#d6e7df}.wrap{max-width:1100px;margin:28px auto;padding:0 20px;display:grid;grid-template-columns:1fr 1fr;gap:20px}.card{background:white;border:1px solid #e4e9e2;border-radius:18px;padding:22px;box-shadow:0 8px 24px #123d320a}.card h2{margin:0 0 4px;font-size:21px}.hint{color:#6b7770;font-size:14px;margin:0 0 18px}.field{display:grid;gap:6px;margin:12px 0}.field label{font-size:13px;font-weight:650;color:#46534b}.field input,.field textarea,.field select{width:100%;border:1px solid #dbe2dc;border-radius:10px;padding:11px;font:inherit;background:#fcfdfb}.field textarea{min-height:76px;resize:vertical}.row{display:grid;grid-template-columns:1fr 1fr;gap:10px}.btn{border:0;border-radius:10px;padding:11px 15px;background:#16654f;color:white;font-weight:700;cursor:pointer}.btn:hover{background:#0f4d3b}.btn.secondary{background:#ecf3ee;color:#175541}.btn.danger{background:#fff0ed;color:#a83d2b;padding:7px 10px}.actions{display:flex;gap:8px;align-items:center}.items{display:grid;gap:10px;margin-top:18px}.item{border:1px solid #e8ece8;border-radius:12px;padding:14px;display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.item strong{display:block}.muted{font-size:13px;color:#718078;margin-top:4px}.badge{display:inline-flex;border-radius:30px;background:#eef6df;color:#55751e;padding:4px 9px;font-size:12px;font-weight:700}.full{grid-column:1/-1}.empty{padding:18px;text-align:center;border:1px dashed #d4ddd5;border-radius:12px;color:#728077;font-size:14px}.status{position:fixed;right:20px;bottom:18px;background:#173d32;color:white;padding:12px 16px;border-radius:12px;display:none;max-width:360px}.stars{color:#d28b15;font-weight:700}@media(max-width:760px){.wrap{grid-template-columns:1fr}.full{grid-column:auto}.top{padding:24px}}
                </style></head><body><header class="top"><small>Projeto NoSQL · etcd</small><h1>Cantina em Dia</h1><p>Cardápio do dia e avaliações da comunidade escolar.</p></header>
                <main class="wrap"><section class="card"><h2>Cardápio</h2><p class="hint">Cadastre pratos para a data selecionada.</p><div class="field"><label for="date">Data</label><input id="date" type="date"></div><form id="menuForm"><input id="dishId" type="hidden"><div class="field"><label for="name">Nome do prato</label><input id="name" required maxlength="80" placeholder="Ex.: prato feito"></div><div class="field"><label for="description">Descrição</label><textarea id="description" maxlength="240" placeholder="Acompanhamentos, opções... "></textarea></div><div class="row"><div class="field"><label for="price">Preço (R$)</label><input id="price" type="number" min="0" step="0.01" required placeholder="18,00"></div><div class="field"><label for="category">Categoria</label><select id="category"><option>Almoço</option><option>Lanche</option><option>Bebida</option><option>Sobremesa</option></select></div></div><button class="btn" id="saveDish">Adicionar prato</button> <button class="btn secondary" type="button" id="cancelEdit" hidden>Cancelar edição</button></form><div class="items" id="menuItems"></div></section>
                <section class="card"><h2>Avaliar um prato</h2><p class="hint">Deixe uma nota de 1 a 5 e um comentário opcional.</p><form id="reviewForm"><input id="reviewId" type="hidden"><div class="field"><label for="dishSelect">Prato</label><select id="dishSelect" required></select></div><div class="row"><div class="field"><label for="rating">Nota</label><select id="rating"><option value="5">5 — Excelente</option><option value="4">4 — Muito bom</option><option value="3">3 — Bom</option><option value="2">2 — Regular</option><option value="1">1 — Ruim</option></select></div><div class="field"><label for="reviewer">Seu nome (opcional)</label><input id="reviewer" maxlength="60" placeholder="Como podemos chamar você?"></div></div><div class="field"><label for="comment">Comentário (opcional)</label><textarea id="comment" maxlength="240" placeholder="Conte o que achou..."></textarea></div><button class="btn" id="saveReview">Enviar avaliação</button> <button class="btn secondary" type="button" id="cancelReviewEdit" hidden>Cancelar edição</button></form><div class="items" id="reviewItems"></div></section>
                <section class="card full"><h2>Sobre este protótipo</h2><p class="hint">A aplicação Java salva pratos e avaliações no etcd como pares chave-valor. Chaves por data permitem buscar os registros do dia; a interface oferece criação, consulta, edição e remoção.</p></section></main><div class="status" id="status"></div>
                <script>
                const $=s=>document.querySelector(s), today=new Date(), pad=n=>String(n).padStart(2,'0');$('#date').value=`${today.getFullYear()}-${pad(today.getMonth()+1)}-${pad(today.getDate())}`;let dishes=[],reviews=[];
                function toast(m){const e=$('#status');e.textContent=m;e.style.display='block';setTimeout(()=>e.style.display='none',3000)}
                async function api(url,opt={}){const r=await fetch(url,{headers:{'Content-Type':'application/json'},...opt});const d=await r.json();if(!r.ok)throw Error(d.error||'Falha na requisição');return d}
                function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
                async function refresh(){const date=$('#date').value;try{[dishes,reviews]=await Promise.all([api('/api/menu?date='+date),api('/api/reviews?date='+date)]);render()}catch(e){toast('Não conectou ao etcd. Confira se a janela do servidor continua aberta.')}}
                function render(){const menu=$('#menuItems');menu.innerHTML=dishes.length?dishes.map(d=>{const ratings=reviews.filter(r=>r.dishId===d.id);const avg=ratings.length?(ratings.reduce((sum,r)=>sum+Number(r.rating),0)/ratings.length).toFixed(1):'—';return `<article class="item"><div><strong>${esc(d.name)} <span class="badge">${esc(d.category)}</span></strong><div class="muted">${esc(d.description)} · R$ ${Number(d.price||0).toFixed(2).replace('.',',')}</div><div class="muted">★ ${avg} · ${ratings.length} avaliação(ões)</div></div><div class="actions"><button class="btn secondary" onclick="editDish('${d.id}')">Editar</button><button class="btn danger" onclick="removeDish('${d.id}')">Excluir</button></div></article>`}).join(''):'<div class="empty">Nenhum prato cadastrado para esta data.</div>';const select=$('#dishSelect');select.innerHTML=dishes.length?dishes.map(d=>`<option value="${d.id}">${esc(d.name)}</option>`).join(''):'<option value="">Cadastre um prato primeiro</option>';const filtered=reviews.filter(r=>dishes.some(d=>d.id===r.dishId));$('#reviewItems').innerHTML=filtered.length?filtered.map(r=>`<article class="item"><div><strong>${esc(r.reviewer||'Avaliação anônima')} <span class="stars">${'★'.repeat(Number(r.rating))}${'☆'.repeat(5-Number(r.rating))}</span></strong><div class="muted">${esc(r.comment||'Sem comentário')} · ${esc(dishes.find(d=>d.id===r.dishId)?.name||'')}</div></div><div class="actions"><button class="btn secondary" onclick="editReview('${r.id}','${r.dishId}')">Editar</button><button class="btn danger" onclick="removeReview('${r.id}','${r.dishId}')">Excluir</button></div></article>`).join(''):'<div class="empty">As avaliações dos pratos aparecerão aqui.</div>'}
                $('#menuForm').addEventListener('submit',async e=>{e.preventDefault();const id=$('#dishId').value,data={date:$('#date').value,name:$('#name').value,description:$('#description').value,price:$('#price').value,category:$('#category').value};try{await api(id?`/api/menu/${id}?date=${data.date}`:'/api/menu',{method:id?'PUT':'POST',body:JSON.stringify(data)});e.target.reset();$('#dishId').value='';$('#saveDish').textContent='Adicionar prato';$('#cancelEdit').hidden=true;toast(id?'Prato atualizado':'Prato cadastrado');refresh()}catch(err){toast(err.message)}});
                window.editDish=id=>{const d=dishes.find(x=>x.id===id);if(!d)return;$('#dishId').value=d.id;$('#name').value=d.name;$('#description').value=d.description||'';$('#price').value=d.price;$('#category').value=d.category||'Almoço';$('#saveDish').textContent='Salvar alterações';$('#cancelEdit').hidden=false};window.removeDish=async id=>{if(!confirm('Excluir este prato e suas avaliações?'))return;try{await api(`/api/menu/${id}?date=${$('#date').value}`,{method:'DELETE'});toast('Prato excluído');refresh()}catch(e){toast(e.message)}};$('#cancelEdit').onclick=()=>{ $('#menuForm').reset();$('#dishId').value='';$('#saveDish').textContent='Adicionar prato';$('#cancelEdit').hidden=true};
                window.editReview=(id,dishId)=>{const r=reviews.find(x=>x.id===id);if(!r)return;$('#reviewId').value=id;$('#dishSelect').value=dishId;$('#rating').value=r.rating;$('#reviewer').value=r.reviewer||'';$('#comment').value=r.comment||'';$('#saveReview').textContent='Salvar avaliação';$('#cancelReviewEdit').hidden=false};$('#cancelReviewEdit').onclick=()=>{ $('#reviewForm').reset();$('#reviewId').value='';$('#saveReview').textContent='Enviar avaliação';$('#cancelReviewEdit').hidden=true};$('#reviewForm').addEventListener('submit',async e=>{e.preventDefault();const dish=dishes.find(d=>d.id===$('#dishSelect').value);if(!dish)return;const id=$('#reviewId').value,data={date:$('#date').value,dishId:dish.id,rating:$('#rating').value,reviewer:$('#reviewer').value,comment:$('#comment').value};try{await api(id?`/api/reviews/${id}?date=${data.date}&dishId=${data.dishId}`:'/api/reviews',{method:id?'PUT':'POST',body:JSON.stringify(data)});$('#reviewForm').reset();$('#reviewId').value='';$('#saveReview').textContent='Enviar avaliação';$('#cancelReviewEdit').hidden=true;toast(id?'Avaliação atualizada':'Avaliação registrada');refresh()}catch(err){toast(err.message)}});window.removeReview=async(id,dishId)=>{try{await api(`/api/reviews/${id}?date=${$('#date').value}&dishId=${dishId}`,{method:'DELETE'});toast('Avaliação removida');refresh()}catch(e){toast(e.message)}};$('#date').addEventListener('change',refresh);refresh();
                </script></body></html>
                """;
    }
}
