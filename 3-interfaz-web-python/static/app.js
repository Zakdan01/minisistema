/* ==========================================================================
   Mondo - logica del navegador

   Este archivo es el unico que decide que se ve en pantalla. Pide los
   datos a servidor.py con fetch() y pinta el resultado en el DOM.

   Las 3 operaciones de la interfaz:

     1. Fichas    -> GET /api/paises
     2. Comparar  -> POST /api/comparar
     3. Buscar    -> GET /api/buscar

   La 4a pestana, el resumen por region, se lee de /api/resumen: esos 6
   resultados los escribio el job de Apache Flink en MongoDB, no se
   calculan aqui. Ver GUIA-FLINK.md.

   El archivo esta ordenado por secciones, de arriba abajo:

     Peticiones     el.envoltorio pedir() y el atajo api{}
     Estado         los paises ya cargados y la seleccion de comparar
     Fichas         pintarFichas() y el filtro por texto y region
     Comparar       las casillas, ejecutarComparacion() y el resultado
     Buscar         ejecutarBusqueda(), que combina todos los filtros
     Resumen        la tabla que escribio Flink
     Pestanas       cambiarVista()
     Arranque       iniciar(), que es lo unico que corre al abrir la pagina

   Detalle a tener en cuenta: el filtro de texto de Fichas se resuelve en
   el navegador sobre los 250 paises ya descargados, porque se descargan
   todos de una vez. El de la pestana Buscar si va al servidor, porque
   combina condiciones que MongoDB resuelve con indices.
   ========================================================================== */

'use strict';

const $ = (sel) => document.querySelector(sel);

const numberFormat = new Intl.NumberFormat('es-ES');

const fmt = (n) => numberFormat.format(n ?? 0);

/** 1.278.000.000 -> "1.278 mil M" */
function fmtCorto(n) {
  n = n ?? 0;
  if (n >= 1e9) return (n / 1e9).toFixed(2).replace('.', ',') + ' mil M';
  if (n >= 1e6) return (n / 1e6).toFixed(1).replace('.', ',') + ' M';
  if (n >= 1e3) return (n / 1e3).toFixed(0) + ' mil';
  return String(n);
}

/* ==========================================================================
   Peticiones
   ========================================================================== */

async function pedir(ruta, opciones) {
  const respuesta = await fetch(ruta, opciones);
  if (!respuesta.ok) {
    let detalle = respuesta.statusText;
    try {
      const cuerpo = await respuesta.json();
      if (cuerpo.error) detalle = cuerpo.error;
    } catch (_) { /* la respuesta no era JSON */ }
    throw new Error(detalle);
  }
  return respuesta.json();
}

const api = {
  paises:   ()            => pedir('/api/paises'),
  resumen:  ()            => pedir('/api/resumen'),
  opciones: ()            => pedir('/api/opciones'),
  buscar:   (q)           => pedir('/api/buscar?' + new URLSearchParams(q)),
  comparar: (codigos)     => pedir('/api/comparar', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ codigos }),
            }),
  estado:   ()            => pedir('/api/estado'),
};

/* ==========================================================================
   Estado
   ========================================================================== */

const estado = {
  paises:      [],     // los 250, se cargan una vez
  seleccion:   new Set(),
  datosResumen: null,
};

/* ==========================================================================
   Vista: FICHAS
   ========================================================================== */

function pintarFichas(contenedor, paises, conCheckbox) {
  if (paises.length === 0) {
    contenedor.innerHTML = `
      <div class="vacio">
        <span class="grande">∅</span>
        Ningun pais coincide con esos filtros.
      </div>`;
    return;
  }

  contenedor.innerHTML = paises.map((p) => {
    const capitals = p.capital.length ? p.capital.join(', ') : '-';
    const monedas  = p.monedas.map(m => m.simbolo ? `${m.nombre} ${m.simbolo}` : m.nombre);
    const idiomas  = p.idiomas.length ? p.idiomas.join(', ') : '-';

    const etiquetas = [
      ...monedas.slice(0, 2).map(m => `<span class="etiqueta">${escapar(m)}</span>`),
      ...p.idiomas.slice(0, 2).map(i => `<span class="etiqueta">${escapar(i)}</span>`),
      ...(monedas.length > 2 || p.idiomas.length > 2
          ? [`<span class="etiqueta etiqueta-extra">+${monedas.length + p.idiomas.length - 4}</span>`]
          : []),
    ].join('');

    const marcado = estado.seleccion.has(p.codigo);
    const marcada = marcado ? 'ficha-seleccionada' : '';

    const check = conCheckbox ? `
      <label class="check-ficha" onclick="event.stopPropagation()">
        <input type="checkbox" data-codigo="${p.codigo}" ${marcado ? 'checked' : ''}>
        Anadir a la comparacion
      </label>` : '';

    return `
      <article class="ficha ${conCheckbox ? marcada : ''}">
        <div class="ficha-cabecera">
          <div>
            <h3 class="ficha-nombre">${escapar(p.nombre)}</h3>
            <p class="ficha-oficial">${escapar(p.nombre_oficial)}</p>
          </div>
          <span class="codigo">${p.codigo}</span>
        </div>

        <dl class="ficha-datos">
          <dt>Region</dt>          <dd>${escapar(p.region)}</dd>
          <dt>Subregion</dt>       <dd>${escapar(p.subregion)}</dd>
          <dt>Capital</dt>         <dd>${escapar(capitals)}</dd>
          <dt>Poblacion</dt>       <dd>${fmt(p.poblacion)}</dd>
          <dt>Superficie</dt>      <dd>${fmt(p.superficie)} km2</dd>
          <dt>Densidad</dt>        <dd>${p.densidad.toFixed(1)} hab/km2</dd>
          <dt>Fronteras</dt>       <dd>${p.fronteras.length}</dd>
        </dl>

        <div class="etiquetas">${etiquetas}</div>
        ${check}
      </article>`;
  }).join('');
}

/** Escapa texto para meterlo en HTML sin que se ejecute. */
function escapar(texto) {
  const div = document.createElement('div');
  div.textContent = texto ?? '';
  return div.innerHTML;
}

/** Filtra los 250 ya cargados, sin volver a preguntar al servidor. */
function filtrarFichas() {
  const texto  = $('#fichaTexto').value.trim().toLowerCase();
  const region = $('#fichaRegion').value;

  let lista = estado.paises;

  if (region) {
    lista = lista.filter(p => p.region === region);
  }

  if (texto) {
    lista = lista.filter(p => {
      const capital = p.capital.join(' ').toLowerCase();
      return p.nombre.toLowerCase().includes(texto)
          || p.nombre_oficial.toLowerCase().includes(texto)
          || capital.includes(texto)
          || p.region.toLowerCase().includes(texto)
          || p.subregion.toLowerCase().includes(texto);
    });
  }

  pintarFichas($('#fichas'), lista, false);
  $('#fichaContador').textContent =
    `${lista.length} de ${estado.paises.length} paises`;
}

/* ==========================================================================
   Vista: COMPARAR
   ========================================================================== */

function pintarListaCheckbox() {
  const texto = $('#compararTexto').value.trim().toLowerCase();

  let lista = estado.paises;
  if (texto) {
    lista = lista.filter(p =>
      p.nombre.toLowerCase().includes(texto) ||
      p.capital.join(' ').toLowerCase().includes(texto));
  }

  // Si hay texto de busqueda se muestran los 60 primeros, para no
  // pintar 250 checkbox de golpe.
  if (texto) lista = lista.slice(0, 60);

  $('#listaCheckbox').innerHTML = lista.length === 0
    ? '<div class="vacio">Ningun pais coincide.</div>'
    : lista.map(p => `
        <label class="item-checkbox">
          <input type="checkbox" data-codigo="${p.codigo}"
                 ${estado.seleccion.has(p.codigo) ? 'checked' : ''}>
          <span>${escapar(p.nombre)}</span>
          <span class="codigo-mini">${p.codigo}</span>
        </label>`).join('');
}

function actualizarContadorSeleccion() {
  const n = estado.seleccion.size;
  $('#compararContador').textContent = n === 1 ? '1 seleccionado' : `${n} seleccionados`;
  $('#compararEjecutar').disabled = n === 0;
}

async function ejecutarComparacion() {
  if (estado.seleccion.size === 0) return;

  $('#compararEjecutar').disabled = true;
  $('#compararEjecutar').textContent = 'Comparando...';

  try {
    const datos = await api.comparar([...estado.seleccion]);
    pintarComparacion(datos);
  } catch (e) {
    $('#resultadoComparar').innerHTML =
      `<div class="vacio">No se pudo comparar: ${escapar(e.message)}</div>`;
  } finally {
    actualizarContadorSeleccion();
    $('#compararEjecutar').textContent = 'Comparar';
  }
}

function pintarComparacion(datos) {
  const zona = $('#resultadoComparar');

  if (datos.paises.length === 0) {
    zona.innerHTML = '<div class="vacio">No hay paises para comparar.</div>';
    return;
  }

  const maximo = Math.max(...datos.paises.map(p => p.poblacion));

  // --- Barras ---
  const barras = datos.paises.map(p => {
    const porcentaje = (p.poblacion / maximo) * 100;
    let clase = '';
    if (p.codigo === datos.mayor) clase = 'mayor';
    if (p.codigo === datos.menor) clase = 'menor';
    return `
      <div class="barra-fila ${clase}">
        <div class="barra-etiqueta">
          <span>${escapar(p.nombre)} <span class="codigo">${p.codigo}</span></span>
          <span class="valor">${fmt(p.poblacion)} &middot; ${p.porcentajeComparacion.toFixed(1)}%</span>
        </div>
        <div class="barra-pista">
          <div class="barra-relleno" style="width:${porcentaje.toFixed(2)}%"></div>
        </div>
      </div>`;
  }).join('');

  // --- Cajas de resumen ---
  const nombreMayor = datos.paises.find(p => p.codigo === datos.mayor);
  const nombreMenor = datos.paises.find(p => p.codigo === datos.menor);

  const cajas = `
    <div class="resumen-cajas">
      <div class="caja">
        <div class="caja-etiqueta">Total comparado</div>
        <div class="caja-valor">${fmt(datos.total)}</div>
        <div class="caja-detalle">${datos.paises.length} paises</div>
      </div>
      <div class="caja">
        <div class="caja-etiqueta">Del mundo</div>
        <div class="caja-valor">${(datos.total / datos.poblacionMundial * 100).toFixed(1)}%</div>
        <div class="caja-detalle">de ${fmt(datos.poblacionMundial)} hab</div>
      </div>
      <div class="caja">
        <div class="caja-etiqueta">Mayor</div>
        <div class="caja-valor">${escapar(nombreMayor ? nombreMayor.nombre : '-')}</div>
        <div class="caja-detalle">${nombreMenor ? `x${datos.razon.toFixed(1)} el menor` : ''}</div>
      </div>
      <div class="caja">
        <div class="caja-etiqueta">Menor</div>
        <div class="caja-valor">${escapar(nombreMenor ? nombreMenor.nombre : '-')}</div>
        <div class="caja-detalle">${nombreMenor ? fmt(nombreMenor.poblacion) + ' hab' : ''}</div>
      </div>
    </div>`;

  // --- Tabla ---
  const filas = datos.paises.map(p => `
    <tr>
      <td><span class="codigo">${p.codigo}</span></td>
      <td>${escapar(p.nombre)}</td>
      <td>${escapar(p.region)}</td>
      <td class="num">${fmt(p.poblacion)}</td>
      <td class="num">${p.porcentajeComparacion.toFixed(2)}%</td>
      <td class="num">${p.porcentajeMundial.toFixed(2)}%</td>
      <td class="num">${fmt(p.superficie)}</td>
      <td class="num">${p.densidad.toFixed(1)}</td>
    </tr>`).join('');

  const tabla = `
    <table class="tabla">
      <thead>
        <tr>
          <th>Codigo</th>
          <th>Pais</th>
          <th>Region</th>
          <th class="num">Poblacion</th>
          <th class="num">% comparacion</th>
          <th class="num">% mundo</th>
          <th class="num">Superficie</th>
          <th class="num">Densidad</th>
        </tr>
      </thead>
      <tbody>${filas}</tbody>
    </table>`;

  zona.innerHTML = `
    <h3>Resultado de la comparacion</h3>
    ${cajas}
    <h3>Barras de poblacion</h3>
    <div class="barras">${barras}</div>
    <h3>Tabla completa</h3>
    ${tabla}`;
}

/* ==========================================================================
   Vista: BUSCAR
   ========================================================================== */

async function ejecutarBusqueda() {
  const parametros = {};

  const pares = [
    ['q',              $('#bTexto').value.trim()],
    ['region',         $('#bRegion').value],
    ['moneda',         $('#bMoneda').value.trim()],
    ['idioma',         $('#bIdioma').value.trim()],
    ['superficieMin',  $('#bSupMin').value],
    ['superficieMax',  $('#bSupMax').value],
    ['poblacionMin',   $('#bPobMin').value],
    ['poblacionMax',   $('#bPobMax').value],
  ];

  for (const [clave, valor] of pares) {
    if (valor) parametros[clave] = valor;
  }

  $('#resultadoBuscar').innerHTML = '<div class="cargando">Buscando...</div>';

  try {
    const datos = await api.buscar(parametros);
    pintarFichas($('#resultadoBuscar'), datos.paises, true);
    $('#bContador').textContent =
      datos.paises.length === 1 ? '1 pais encontrado' : `${datos.paises.length} paises encontrados`;
  } catch (e) {
    $('#resultadoBuscar').innerHTML =
      `<div class="vacio">Error al buscar: ${escapar(e.message)}</div>`;
    $('#bContador').textContent = '';
  }
}

function limpiarBusqueda() {
  ['#bTexto', '#bMoneda', '#bIdioma', '#bSupMin', '#bSupMax',
   '#bPobMin', '#bPobMax'].forEach(s => { $(s).value = ''; });
  $('#bRegion').value = '';
  $('#resultadoBuscar').innerHTML = '';
  $('#bContador').textContent = '';
}

/* ==========================================================================
   Vista: RESUMEN DE FLINK
   ========================================================================== */

async function cargarResumen() {
  const zona = $('#resumenTabla');
  zona.innerHTML = '<div class="cargando">cargando...</div>';

  try {
    const datos = await api.resumen();

    if (datos.regiones.length === 0) {
      zona.innerHTML = `
        <div class="vacio">
          <span class="grande">⚠</span>
          El job de Apache Flink todavia no ha corrido.<br>
          Ejecuta <code>2-sistema-flink-java\\run.bat</code> y recarga.
        </div>`;
      return;
    }

    estado.datosResumen = datos;
    $('#pieTotal').textContent = fmt(datos.mundial.poblacion_total);

    const filas = datos.regiones.map(r => `
      <tr>
        <td>${escapar(r.region)}</td>
        <td class="num">${r.cantidad}</td>
        <td class="num">${fmt(r.poblacion)}</td>
        <td class="num">${fmt(r.superficie)}</td>
        <td class="num">${fmt(r.poblacionMaxima)}</td>
        <td>${escapar(r.paisMasPoblado)}</td>
        <td class="num">${r.grandes}</td>
      </tr>`).join('');

    const m = datos.mundial;

    zona.innerHTML = `
      <table class="tabla">
        <thead>
          <tr>
            <th>Region</th>
            <th class="num">Paises</th>
            <th class="num">Poblacion</th>
            <th class="num">Superficie km2</th>
            <th class="num">Poblacion max</th>
            <th>Pais mas poblado</th>
            <th class="num">&gt;50M hab</th>
          </tr>
        </thead>
        <tbody>
          ${filas}
          <tr style="background:var(--superficie-2); font-weight:600">
            <td>TOTAL</td>
            <td class="num">${m.paises}</td>
            <td class="num">${fmt(m.poblacion_total)}</td>
            <td class="num">${fmt(m.superficie)}</td>
            <td class="num">-</td>
            <td>-</td>
            <td class="num">${m.grandes}</td>
          </tr>
        </tbody>
      </table>

      <div class="resumen-cajas" style="margin-top:24px">
        <div class="caja">
          <div class="caja-etiqueta">Paises procesados</div>
          <div class="caja-valor">${m.paises}</div>
          <div class="caja-detalle">leidos por Flink</div>
        </div>
        <div class="caja">
          <div class="caja-etiqueta">Poblacion total</div>
          <div class="caja-valor">${fmtCorto(m.poblacion_total)}</div>
          <div class="caja-detalle">${fmt(m.poblacion_total)} hab</div>
        </div>
        <div class="caja">
          <div class="caja-etiqueta">Superficie total</div>
          <div class="caja-valor">${fmtCorto(m.superficie)}</div>
          <div class="caja-detalle">km2</div>
        </div>
        <div class="caja">
          <div class="caja-etiqueta">Paises &gt; 50M hab</div>
          <div class="caja-valor">${m.grandes}</div>
          <div class="caja-detalle">de ${m.paises}</div>
        </div>
      </div>`;

  } catch (e) {
    zona.innerHTML = `<div class="vacio">Error: ${escapar(e.message)}</div>`;
  }
}

/* ==========================================================================
   Pestanas
   ========================================================================== */

function cambiarVista(nombre) {
  document.querySelectorAll('.pestana').forEach(p => {
    p.classList.toggle('activa', p.dataset.vista === nombre);
  });
  document.querySelectorAll('.vista').forEach(v => {
    v.classList.toggle('activa', v.id === 'vista-' + nombre);
  });

  // El resumen se pide la primera vez que se abre, no al arrancar.
  if (nombre === 'resumen' && !estado.datosResumen) {
    cargarResumen();
  }
}

/* ==========================================================================
   Estado del sistema
   ========================================================================== */

async function refrescarEstado() {
  const caja = $('#estado');
  const texto = $('#estadoTexto');

  try {
    const info = await api.estado();

    if (!info.mongo) {
      caja.className = 'estado error';
      texto.textContent = 'MongoDB no responde';
      return;
    }

    if (info.jobCorrido) {
      caja.className = 'estado ok';
      texto.textContent = `MongoDB ${info.servidor} · ${info.paises} paises · Flink: ${info.regiones} regiones`;
    } else {
      caja.className = 'estado';
      texto.textContent = `MongoDB ${info.servidor} · ${info.paises} paises · job de Flink sin correr`;
    }
  } catch (_) {
    caja.className = 'estado error';
    texto.textContent = 'sin conexion con el servidor';
  }
}

/* ==========================================================================
   Arranque
   ========================================================================== */

function conectarEventos() {
  // --- Pestanas ---
  document.querySelectorAll('.pestana').forEach(p => {
    p.addEventListener('click', () => cambiarVista(p.dataset.vista));
  });

  // --- Fichas ---
  $('#fichaTexto').addEventListener('input', filtrarFichas);
  $('#fichaRegion').addEventListener('change', filtrarFichas);

  // --- Comparar ---
  $('#compararTexto').addEventListener('input', pintarListaCheckbox);
  $('#compararEjecutar').addEventListener('click', ejecutarComparacion);
  $('#compararLimpiar').addEventListener('click', () => {
    estado.seleccion.clear();
    pintarListaCheckbox();
    actualizarContadorSeleccion();
    $('#resultadoComparar').innerHTML = '';
  });

  // Un solo manejador para los checkbox, este y los de las fichas
  // del buscador, porque los dos pintan data-codigo.
  document.addEventListener('change', (evento) => {
    const caja = evento.target;
    if (caja.type !== 'checkbox' || !caja.dataset.codigo) return;

    if (caja.checked) {
      estado.seleccion.add(caja.dataset.codigo);
    } else {
      estado.seleccion.delete(caja.dataset.codigo);
    }

    actualizarContadorSeleccion();
  });

  // --- Buscar ---
  $('#bBuscar').addEventListener('click', ejecutarBusqueda);
  $('#bLimpiar').addEventListener('click', limpiarBusqueda);
  $('#bTexto').addEventListener('keydown', e => { if (e.key === 'Enter') ejecutarBusqueda(); });
  $('#bMoneda').addEventListener('keydown', e => { if (e.key === 'Enter') ejecutarBusqueda(); });
  $('#bIdioma').addEventListener('keydown', e => { if (e.key === 'Enter') ejecutarBusqueda(); });
}

async function cargarDesplegables() {
  try {
    const opciones = await api.opciones();

    $('#bRegion').innerHTML = '<option value="">todas</option>' +
      opciones.regiones.map(r => `<option value="${r}">${r}</option>`).join('');

    $('#fichaRegion').innerHTML = '<select><option value="">todas las regiones</option>' +
      opciones.regiones.map(r => `<option value="${r}">${r}</option>`).join('') + '</select>';

    $('#listaMonedas').innerHTML = opciones.monedas
      .map(m => `<option value="${escapar(m)}">`).join('');
    $('#listaIdiomas').innerHTML = opciones.idiomas
      .map(i => `<option value="${escapar(i)}">`).join('');

  } catch (e) {
    console.error('No se pudieron cargar las opciones:', e);
  }
}

async function iniciar() {
  conectarEventos();
  refrescarEstado();

  $('#fichas').innerHTML = '<div class="cargando">Cargando los 250 paises...</div>';

  try {
    const datos = await api.paises();
    estado.paises = datos.paises;
    filtrarFichas();
  } catch (e) {
    $('#fichas').innerHTML =
      `<div class="vacio">No se pudieron cargar los paises: ${escapar(e.message)}</div>`;
  }

  await cargarDesplegables();

  pintarListaCheckbox();
  actualizarContadorSeleccion();

  // El resumen se pide al abrir la pestana, no aqui, para no gastar
  // una peticion que quizas no se use.
  cargarResumen();
}

document.addEventListener('DOMContentLoaded', iniciar);
