package br.com.rsdata.gestaonr12api.service;

import br.com.rsdata.gestaonr12api.dto.*;
import br.com.rsdata.gestaonr12api.model.*;
import br.com.rsdata.gestaonr12api.model.tags.Tags;
import br.com.rsdata.gestaonr12api.repository.*;
import br.com.rsdata.gestaonr12api.util.ImageCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.docx4j.Docx4J;
import org.docx4j.dml.CTPositiveSize2D;
import org.docx4j.dml.wordprocessingDrawing.Inline;
import org.docx4j.jaxb.Context;
import org.docx4j.model.structure.PageDimensions;
import org.docx4j.model.structure.SectionWrapper;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.exceptions.InvalidFormatException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.*;
import org.docx4j.relationships.Relationship;
import org.docx4j.toc.Toc;
import org.docx4j.toc.TocGenerator;
import org.docx4j.toc.TocHelper;
import org.docx4j.wml.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

import javax.ws.rs.NotFoundException;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class LaudoAvaliacoesService {

    private final String bodyFontSize = "14"; // 7pt * 2 = 14 half-points
    private final String subTitleFontSize = "14"; // 7pt * 2 = 14 half-points
    private final String titleFontSize = "18"; // 9pt * 2 = 18 half-points
    private final int tableWidthPct = 5000; // 100% = 5000

    private final SituacaoAdequacaoCriterioAvaliadoRepository situacaoAdequacaoCriterioAvaliadoRepository;
    private final LaudoAvaliacoesRepository laudoAvaliacoesRepository;
    private final LaudoRepository laudoRepository;
    private final PerigosAvaliacaoRepository perigosAvaliacaoRepository;
    private final PerigoRepository perigoRepository;
    private final ArtLaudoRepository artLaudoRepository;
    private final ItemModeloLaudoRepository itemModeloLaudoRepository;
    private final CriterioAvaliadoRepository criterioAvaliadoRepository;
    private final GrupoCriterioRepository grupoCriterioRepository;
    private final PiorSituacaoRepository piorSituacaoRepository;
    private final FuncaoItemBibliografiaRepository funcaoItemBibliografiaRepository;
    private final GrupoPerigoRepository grupoPerigoRepository;

    private final ObjectFactory factory = Context.getWmlObjectFactory();

    ImageCache imageCache;

    {
        try {
            imageCache = new ImageCache(
                    200L * 1024 * 1024,    // 200 MB de cache
                    24L * 60 * 60 * 1000   // expirar em 24h
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public LaudoAvaliacoes atualizar(Long codigo, LaudoAvaliacoes obj) {
        LaudoAvaliacoes objSalvo = buscarLaudoPeloCodigo(codigo);
        BeanUtils.copyProperties(obj, objSalvo, "id");
        return laudoAvaliacoesRepository.save(objSalvo);
    }

    public LaudoAvaliacoes buscarLaudoPeloCodigo(Long codigo) {
        return laudoAvaliacoesRepository.findById(codigo)
                .orElseThrow(() -> new EmptyResultDataAccessException(1));
    }

    // ==================== MÉTODOS AUXILIARES DOCX4J ====================

    /**
     * Cria um parágrafo com texto e formatação
     */
    private P createParagraph(String text, boolean bold, String fontSize, JcEnumeration alignment, String backgroundColor) {
        P p = factory.createP();

        if (alignment != null) {
            PPr pPr = factory.createPPr();
            Jc jc = factory.createJc();
            jc.setVal(alignment);
            pPr.setJc(jc);
            p.setPPr(pPr);
        }

        if (text != null && !text.isEmpty()) {
            R run = factory.createR();
            Text t = factory.createText();
            t.setValue(text);
            t.setSpace("preserve");
            run.getContent().add(t);

            if (bold || fontSize != null) {
                RPr rpr = factory.createRPr();
                if (bold) {
                    rpr.setB(new BooleanDefaultTrue());
                }
                if (fontSize != null) {
                    HpsMeasure size = factory.createHpsMeasure();
                    size.setVal(new BigInteger(fontSize));
                    rpr.setSz(size);
                    rpr.setSzCs(size);
                }
                run.setRPr(rpr);
            }

            p.getContent().add(run);
        }

        return p;
    }

    /**
     * Cria um parágrafo simples com texto
     */
    private P createSimpleParagraph(String text) {
        return createParagraph(text, false, bodyFontSize, null, null);
    }

    /**
     * Cria um run com texto e formatação
     */
    private R createRun(String text, boolean bold, String fontSize) {
        R run = factory.createR();
        Text t = factory.createText();
        t.setValue(text);
        t.setSpace("preserve");
        run.getContent().add(t);

        RPr rpr = factory.createRPr();
        if (bold) {
            rpr.setB(new BooleanDefaultTrue());
        }
        if (fontSize != null) {
            HpsMeasure size = factory.createHpsMeasure();
            size.setVal(new BigInteger(fontSize));
            rpr.setSz(size);
            rpr.setSzCs(size);
        }
        run.setRPr(rpr);

        return run;
    }

    /**
     * Cria uma célula de tabela
     */
    private Tc createTableCell(String text, int colspan, int rowspan, boolean bold, String fontSize,
                               JcEnumeration alignment, String backgroundColor, STVerticalJc verticalAlignment) {
        Tc tc = factory.createTc();

        // Propriedades da célula
        TcPr tcpr = factory.createTcPr();

        // GridSpan (colspan)
        if (colspan > 1) {
            TcPrInner.GridSpan gridSpan = factory.createTcPrInnerGridSpan();
            gridSpan.setVal(BigInteger.valueOf(colspan));
            tcpr.setGridSpan(gridSpan);
        }

        // VMerge (rowspan) - para rowspan, você precisa criar células VMerge
        if (rowspan > 1) {
            TcPrInner.VMerge vMerge = factory.createTcPrInnerVMerge();
            vMerge.setVal("restart");
            tcpr.setVMerge(vMerge);
        }

        // Alinhamento vertical
        if (verticalAlignment != null) {
            TcPrInner.VAlign vAlign = factory.createTcPrInnerVAlign();
            vAlign.setVal(verticalAlignment);
            tcpr.setVAlign(vAlign);
        }

        // Cor de fundo
        if (backgroundColor != null) {
            CTShd shd = factory.createCTShd();
            shd.setFill(backgroundColor);
            shd.setVal(STShd.CLEAR);
            tcpr.setShd(shd);
        }

        tc.setTcPr(tcpr);

        // Conteúdo da célula
        P p = factory.createP();

        // Alinhamento horizontal
        if (alignment != null) {
            PPr ppr = factory.createPPr();
            Jc jc = factory.createJc();
            jc.setVal(alignment);
            ppr.setJc(jc);
            p.setPPr(ppr);
        }

        if (text != null && !text.isEmpty()) {
            R run = createRun(text, bold, fontSize);
            p.getContent().add(run);
        }

        tc.getContent().add(p);

        return tc;
    }

    /**
     * Cria uma célula com imagem
     */
    private Tc createImageCell(BinaryPartAbstractImage image, WordprocessingMLPackage wordPackage,
                               int colspan, long width, long height, String backgroundColor) throws Exception {
        Tc tc = factory.createTc();

        TcPr tcpr = factory.createTcPr();

        if (colspan > 1) {
            TcPrInner.GridSpan gridSpan = factory.createTcPrInnerGridSpan();
            gridSpan.setVal(BigInteger.valueOf(colspan));
            tcpr.setGridSpan(gridSpan);
        }

        if (backgroundColor != null) {
            CTShd shd = factory.createCTShd();
            shd.setFill(backgroundColor);
            shd.setVal(STShd.CLEAR);
            tcpr.setShd(shd);
        }

        tc.setTcPr(tcpr);

        P p = factory.createP();
        PPr ppr = factory.createPPr();
        Jc jc = factory.createJc();
        jc.setVal(JcEnumeration.CENTER);
        ppr.setJc(jc);
        p.setPPr(ppr);

        if (image != null) {
            Inline inline = image.createImageInline("Image", "Image", 1, 2, false);
            inline.getExtent().setCx(width);
            inline.getExtent().setCy(height);

            if (inline.getGraphic() != null && inline.getGraphic().getGraphicData() != null) {
                inline.getGraphic().getGraphicData().getPic().getSpPr().getXfrm().getExt().setCx(width);
                inline.getGraphic().getGraphicData().getPic().getSpPr().getXfrm().getExt().setCy(height);
            }

            R run = factory.createR();
            Drawing drawing = factory.createDrawing();
            drawing.getAnchorOrInline().add(inline);
            run.getContent().add(drawing);
            p.getContent().add(run);
        }

        tc.getContent().add(p);

        return tc;
    }

    /**
     * Cria propriedades de tabela padrão
     */
    private TblPr createTableProperties(int widthPct) {
        TblPr tblPr = factory.createTblPr();

        // Largura da tabela
        TblWidth tblWidth = factory.createTblWidth();
        tblWidth.setType("pct");
        tblWidth.setW(BigInteger.valueOf(widthPct));
        tblPr.setTblW(tblWidth);

        // Bordas da tabela
        TblBorders borders = factory.createTblBorders();
        CTBorder border = factory.createCTBorder();
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(4));
        border.setColor("000000");

        borders.setTop(border);
        borders.setBottom(border);
        borders.setLeft(border);
        borders.setRight(border);
        borders.setInsideH(border);
        borders.setInsideV(border);
        tblPr.setTblBorders(borders);

        return tblPr;
    }

    /**
     * Remove bordas de uma tabela
     */
    private void removeBorders(TblPr tblPr) {
        TblBorders borders = factory.createTblBorders();
        CTBorder border = factory.createCTBorder();
        border.setVal(STBorder.NONE);

        borders.setTop(border);
        borders.setBottom(border);
        borders.setLeft(border);
        borders.setRight(border);
        borders.setInsideH(border);
        borders.setInsideV(border);
        tblPr.setTblBorders(borders);
    }

    /**
     * Cria uma célula vazia para uso em VMerge
     */
    private Tc createMergedCell() {
        Tc tc = factory.createTc();
        TcPr tcpr = factory.createTcPr();
        TcPrInner.VMerge vMerge = factory.createTcPrInnerVMerge();
        vMerge.setVal("continue");
        tcpr.setVMerge(vMerge);
        tc.setTcPr(tcpr);
        tc.getContent().add(factory.createP());
        return tc;
    }

    /**
     * Adiciona um run com texto a um parágrafo existente
     */
    private void addRunToParagraph(P paragraph, String text, boolean bold, String fontSize) {
        R run = createRun(text, bold, fontSize);
        paragraph.getContent().add(run);
    }

    /**
     * Converte cor hexadecimal para formato sem #
     */
    private String hexToColor(String hexColor) {
        if (hexColor == null) return null;
        return hexColor.startsWith("#") ? hexColor.substring(1) : hexColor;
    }

    // ==================== MÉTODOS DE CRIAÇÃO DE CABEÇALHO/RODAPÉ ====================

    private Relationship createHeaderPart(WordprocessingMLPackage wordMLPackage, Hdr header, HeaderPart headerPart) throws InvalidFormatException {
        headerPart.setPackage(wordMLPackage);
        headerPart.setJaxbElement(header);
        return wordMLPackage.getMainDocumentPart().addTargetPart(headerPart);
    }

    private void createHeaderReference(Relationship relationship, WordprocessingMLPackage wordMLPackage, ObjectFactory factory) {
        List<SectionWrapper> sections = wordMLPackage.getDocumentModel().getSections();
        SectPr sectionProperties = sections.get(sections.size() - 1).getSectPr();

        if (sectionProperties == null) {
            sectionProperties = factory.createSectPr();
            wordMLPackage.getMainDocumentPart().addObject(sectionProperties);
            sections.get(0).setSectPr(sectionProperties);
        }

        HeaderReference headerReference = factory.createHeaderReference();
        headerReference.setId(relationship.getId());
        headerReference.setType(HdrFtrRef.DEFAULT);
        sectionProperties.getEGHdrFtrReferences().add(headerReference);
    }

    private void addImageAndTextToHeader(Inline inline, ObjectFactory factory, Hdr header, String text, String subText) {
        Tbl table = factory.createTbl();

        CTTblCellMar margins = factory.createCTTblCellMar();
        TblWidth marTop = factory.createTblWidth();
        marTop.setW(BigInteger.valueOf(360));
        marTop.setType("dxa");
        margins.setTop(marTop);

        TblPr tableProps = factory.createTblPr();
        TblWidth tableWidth = factory.createTblWidth();
        tableWidth.setType("pct");
        tableWidth.setW(BigInteger.valueOf(5000));
        tableProps.setTblW(tableWidth);
        tableProps.setTblCellMar(margins);
        table.setTblPr(tableProps);

        Tr row = factory.createTr();
        Tc imageCell = factory.createTc();

        P imageParagraph = factory.createP();
        R imageRun = factory.createR();
        Drawing drawing = factory.createDrawing();
        imageRun.getContent().add(drawing);
        drawing.getAnchorOrInline().add(inline);
        imageParagraph.getContent().add(imageRun);
        imageCell.getContent().add(imageParagraph);

        TcPr imageCellProps = factory.createTcPr();
        TblWidth imageCellWidth = factory.createTblWidth();
        imageCellWidth.setType("pct");
        imageCellWidth.setW(BigInteger.valueOf(80));
        imageCellProps.setTcW(imageCellWidth);
        imageCell.setTcPr(imageCellProps);

        row.getContent().add(imageCell);

        RPr runProperties = factory.createRPr();
        BooleanDefaultTrue bold = new BooleanDefaultTrue();
        runProperties.setB(bold);
        HpsMeasure fontSize = factory.createHpsMeasure();
        fontSize.setVal(BigInteger.valueOf(18));
        runProperties.setSz(fontSize);
        runProperties.setSzCs(fontSize);

        PPr paragraphProps = factory.createPPr();
        Jc alignment = factory.createJc();
        alignment.setVal(JcEnumeration.CENTER);
        paragraphProps.setJc(alignment);

        Tc textCell = factory.createTc();
        P textParagraph1 = factory.createP();
        R textRun1 = factory.createR();
        textRun1.setRPr(runProperties);
        Text text1 = factory.createText();
        text1.setValue(text);
        textRun1.getContent().add(text1);
        textParagraph1.setPPr(paragraphProps);
        textParagraph1.getContent().add(textRun1);

        RPr runProperties2 = factory.createRPr();
        HpsMeasure fontSize2 = factory.createHpsMeasure();
        fontSize2.setVal(BigInteger.valueOf(12));
        runProperties2.setSz(fontSize2);
        runProperties2.setSzCs(fontSize2);
        P textParagraph2 = factory.createP();
        R textRun2 = factory.createR();
        textRun2.setRPr(runProperties2);
        Text text2 = factory.createText();
        text2.setValue(subText);
        textRun2.getContent().add(text2);

        textParagraph2.setPPr(paragraphProps);
        textParagraph2.getContent().add(textRun2);

        textCell.getContent().add(textParagraph1);
        textCell.getContent().add(textParagraph2);

        TcPr textCellProps = factory.createTcPr();
        TblWidth textCellWidth = factory.createTblWidth();
        textCellWidth.setType("pct");
        textCellWidth.setW(BigInteger.valueOf(5000));
        textCellProps.setTcW(textCellWidth);
        textCell.setTcPr(textCellProps);

        row.getContent().add(textCell);
        table.getContent().add(row);
        header.getContent().add(table);
    }

    private void addImageToHeader(Inline inline, ObjectFactory factory, Hdr header) {
        Tbl table = factory.createTbl();

        TblPr tableProps = factory.createTblPr();
        TblWidth tableWidth = factory.createTblWidth();
        tableWidth.setType("pct");
        tableWidth.setW(BigInteger.valueOf(5000));
        tableProps.setTblW(tableWidth);
        table.setTblPr(tableProps);

        CTBorder border = factory.createCTBorder();
        border.setVal(STBorder.NONE);
        TblBorders borders = factory.createTblBorders();
        borders.setBottom(border);
        borders.setLeft(border);
        borders.setRight(border);
        borders.setTop(border);
        borders.setInsideH(border);
        borders.setInsideV(border);
        tableProps.setTblBorders(borders);

        Tr row = factory.createTr();
        Tc imageCell = factory.createTc();

        TcPr imageCellProps = factory.createTcPr();
        TblWidth imageCellWidth = factory.createTblWidth();
        imageCellWidth.setType("pct");
        imageCellWidth.setW(BigInteger.valueOf(5000));
        imageCellProps.setTcW(imageCellWidth);
        imageCell.setTcPr(imageCellProps);

        TcMar margins = factory.createTcMar();
        TblWidth marLeft = factory.createTblWidth();
        marLeft.setW(BigInteger.ZERO);
        marLeft.setType("dxa");
        margins.setLeft(marLeft);

        TblWidth marRight = factory.createTblWidth();
        marRight.setW(BigInteger.ZERO);
        marRight.setType("dxa");
        margins.setRight(marRight);

        TblWidth marTop = factory.createTblWidth();
        marTop.setW(BigInteger.valueOf(360));
        marTop.setType("dxa");
        margins.setTop(marTop);

        TblWidth marBottom = factory.createTblWidth();
        marBottom.setW(BigInteger.ZERO);
        marBottom.setType("dxa");
        margins.setBottom(marBottom);

        imageCellProps.setTcMar(margins);

        P imageParagraph = factory.createP();

        PPr pPr = factory.createPPr();
        Jc jc = factory.createJc();
        jc.setVal(JcEnumeration.CENTER);
        pPr.setJc(jc);

        PPrBase.Spacing spacing = factory.createPPrBaseSpacing();
        spacing.setAfter(BigInteger.ZERO);
        spacing.setBefore(BigInteger.ZERO);
        spacing.setLineRule(STLineSpacingRule.AUTO);
        spacing.setLine(BigInteger.valueOf(480));
        pPr.setSpacing(spacing);

        imageParagraph.setPPr(pPr);

        R imageRun = factory.createR();
        Drawing drawing = factory.createDrawing();
        imageRun.getContent().add(drawing);
        drawing.getAnchorOrInline().add(inline);
        imageParagraph.getContent().add(imageRun);

        imageCell.getContent().add(imageParagraph);
        row.getContent().add(imageCell);
        table.getContent().add(row);

        header.getContent().add(table);
    }

    // ==================== MÉTODO PRINCIPAL ====================

    public byte[] callRelatorioAuditoria(Long idLaudo) {
        try {
            return relatorioAuditoria(idLaudo);
        } catch (Exception e) {
            log.error("Erro ao gerar relatório de auditoria", e);
            return null;
        }
    }

    private byte[] relatorioAuditoria(Long idLaudo) throws Exception {
        WordprocessingMLPackage wordPackage = WordprocessingMLPackage.load(new FileInputStream("docs/template.docx"));
        MainDocumentPart mainDocumentPart = wordPackage.getMainDocumentPart();
        TocGenerator tocGenerator = new TocGenerator(wordPackage);
        List<Avaliacao> avaliacoes = new ArrayList<>();

        List<LaudoAvaliacoes> laudosAvaliacoes;
        Map<String, String> tags = new HashMap<>();
        Optional<Laudo> laudo = this.laudoRepository.findById(idLaudo);
        FooterPart footerPart = new FooterPart();
        footerPart.setPackage(wordPackage);

        // Create footer content with page numbers
        P footerParagraph = factory.createP();
        R run = factory.createR();
        FldChar fldCharBegin = factory.createFldChar();
        fldCharBegin.setFldCharType(STFldCharType.END);

        FldChar fldCharSeparate = factory.createFldChar();
        fldCharSeparate.setFldCharType(STFldCharType.SEPARATE);

        FldChar fldCharEnd = factory.createFldChar();
        fldCharEnd.setFldCharType(STFldCharType.END);

        run.getContent().add(fldCharBegin);
        run.getContent().add(fldCharSeparate);
        run.getContent().add(fldCharEnd);

        footerParagraph.getContent().add(run);
        footerPart.setJaxbElement(factory.createFtr());
        footerPart.getJaxbElement().getContent().add(footerParagraph);

        Relationship footerRel = wordPackage.getMainDocumentPart().addTargetPart(footerPart);

        SectPr sectPr = wordPackage.getDocumentModel().getSections().get(0).getSectPr();
        if (sectPr == null) {
            sectPr = factory.createSectPr();
            wordPackage.getMainDocumentPart().addObject(sectPr);
        }

        FooterReference footerReference = factory.createFooterReference();
        footerReference.setId(footerRel.getId());
        footerReference.setType(HdrFtrRef.DEFAULT);
        sectPr.getEGHdrFtrReferences().add(footerReference);

        // Numbering configuration
        NumberingDefinitionsPart numberingPart = new NumberingDefinitionsPart();
        wordPackage.getMainDocumentPart().addTargetPart(numberingPart);

        Numbering numbering = new Numbering();
        Numbering.AbstractNum abstractNum = new Numbering.AbstractNum();
        abstractNum.setAbstractNumId(BigInteger.ZERO);

        for (int i = 0; i < 3; i++) {
            Lvl lvl = new Lvl();
            lvl.setIlvl(BigInteger.valueOf(i));

            NumFmt numFmt = new NumFmt();
            numFmt.setVal(NumberFormat.DECIMAL);

            Lvl.LvlText lvlText = new Lvl.LvlText();
            lvlText.setVal("%1.");

            if (i == 1) {
                lvlText.setVal("%1.%2.");
            } else if (i == 2) {
                lvlText.setVal("%1.%2.%3.");
            }

            Lvl.Start start = new Lvl.Start();
            start.setVal(BigInteger.ONE);

            lvl.setStart(start);
            lvl.setNumFmt(numFmt);
            lvl.setLvlText(lvlText);

            abstractNum.getLvl().add(lvl);
        }

        numbering.getAbstractNum().add(abstractNum);

        Numbering.Num num = new Numbering.Num();
        num.setNumId(BigInteger.ONE);
        num.setAbstractNumId(new Numbering.Num.AbstractNumId());
        num.getAbstractNumId().setVal(BigInteger.ZERO);

        numbering.getNum().add(num);
        numberingPart.setJaxbElement(numbering);

        String[] headings = {"Titulo1Quebra", "Titulo2Quebra", "Titulo3Quebra"};
        for (int i = 0; i < headings.length; i++) {
            Style style = wordPackage.getMainDocumentPart()
                    .getStyleDefinitionsPart()
                    .getStyleById(headings[i]);

            if (style.getPPr() == null)
                style.setPPr(new PPr());

            PPrBase.NumPr numPr = new PPrBase.NumPr();
            PPrBase.NumPr.NumId numIdObj = new PPrBase.NumPr.NumId();
            numIdObj.setVal(BigInteger.ONE);
            numPr.setNumId(numIdObj);

            PPrBase.NumPr.Ilvl ilvl = new PPrBase.NumPr.Ilvl();
            ilvl.setVal(BigInteger.valueOf(i));
            numPr.setIlvl(ilvl);

            style.getPPr().setNumPr(numPr);
        }

        // Header setup
        CompletableFuture<HeaderPart> headerFuture = CompletableFuture.supplyAsync(() -> {
            try {
                HeaderPart headerPart = new HeaderPart();
                Hdr header = factory.createHdr();
                wordPackage.getMainDocumentPart().addTargetPart(headerPart);
                Relationship relationship = createHeaderPart(wordPackage, header, headerPart);
                createHeaderReference(relationship, wordPackage, factory);
                return headerPart;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        if (laudo.isEmpty()) {
            return null;
        }
        Laudo laudoData = laudo.get();

        if (laudoData.getCabecalho().getNomeArquivoImagemCabecalho() != null) {
            CompletableFuture<BinaryPartAbstractImage> imageFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    String url = laudoData.getCabecalho().getCaminhoArquivoCabecalho();
                    if (url != null && !url.isBlank()) {
                        Path imgPath = imageCache.getImageFile(url, laudoData.getCabecalho().getNomeArquivoImagemCabecalho());
                        if (imgPath != null && Files.exists(imgPath)) {
                            HeaderPart headerPart = headerFuture.get();
                            return BinaryPartAbstractImage.createImagePart(wordPackage, headerPart, imgPath.toFile());
                        }
                    }
                    return null;
                } catch (Exception e) {
                    log.error("Erro ao processar imagem do cabecalho", e);
                    throw new RuntimeException(e);
                }
            });

            int emuWidth = 200 * 15;
            BinaryPartAbstractImage image = imageFuture.get();
            if (image != null) {
                Inline inline = image.createImageInline("Filename hint", "Alternative text", 1, 2, emuWidth, false);
                PageDimensions page = wordPackage.getDocumentModel().getSections().get(0).getPageDimensions();
                int writableWidthTwips = page.getWritableWidthTwips();
                long widthEmu = writableWidthTwips * 635L;
                long heightEmu = 600000;

                inline.getExtent().setCx(widthEmu);
                inline.getExtent().setCy(heightEmu);

                CTPositiveSize2D extent = inline.getExtent();
                extent.setCx(widthEmu);
                extent.setCy(heightEmu);

                if (inline.getGraphic() != null) {
                    inline.getGraphic().getGraphicData()
                            .getPic().getSpPr().getXfrm().getExt()
                            .setCx(widthEmu);
                    inline.getGraphic().getGraphicData()
                            .getPic().getSpPr().getXfrm().getExt()
                            .setCy(heightEmu);
                }
                addImageToHeader(inline, factory, headerFuture.get().getContents());
            }
        } else {
            CompletableFuture<BinaryPartAbstractImage> imageFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    String url = laudoData.getCabecalho().getCaminhoArquivo();
                    if (url != null && !url.isBlank()) {
                        Path imgPath = imageCache.getImageFile(url, laudoData.getCabecalho().getNomeArquivo());
                        if (imgPath != null && Files.exists(imgPath)) {
                            HeaderPart headerPart = headerFuture.get();
                            return BinaryPartAbstractImage.createImagePart(wordPackage, headerPart, imgPath.toFile());
                        }
                    }
                    return null;
                } catch (Exception e) {
                    log.error("Erro ao processar imagem do cabecalho com texto", e);
                    throw new RuntimeException(e);
                }
            });

            int emuWidth = 200 * 15;
            BinaryPartAbstractImage image = imageFuture.get();
            if (image != null) {
                Inline inLine = image.createImageInline("Filename hint", "Alternative text", 1, 2, emuWidth, false);
                addImageAndTextToHeader(inLine, factory, headerFuture.get().getContents(),
                    laudoData.getCabecalho().getRazaoSocial(),
                    laudoData.getCabecalho().getEnderecoCidade() + " - " + laudoData.getCabecalho().getEnderecoEstado());
            }
        }

        laudosAvaliacoes = this.laudoAvaliacoesRepository.findByIdLaudo(laudoData);

        for (LaudoAvaliacoes laudosAvaliacoe : laudosAvaliacoes) {
            avaliacoes.add(laudosAvaliacoe.getIdAvaliacao());
        }

        Setor setor = avaliacoes.get(0).getIdSetor();
        List<ArtLaudo> arts = artLaudoRepository.findArtLaudoByIdLaudo(laudoData);

        // Populate tags
        tags.put(Tags.DATA_PRIMEIRA_AVALIACAO, this.laudoRepository.getDataPrimeiraAvaliacao(idLaudo));
        tags.put(Tags.QNTD_EQUIP_AVALIADOS, String.valueOf(avaliacoes.size()));
        tags.put(Tags.EMPRESA_AVALIADA_SETOR, setor.getIdentificacao());
        tags.put(Tags.EMPRESA_AVALIADA_CNPJ, laudoData.getEmpresaAvaliada().getDocumento());
        tags.put(Tags.EMPRESA_AVALIADA_RZS, laudoData.getEmpresaAvaliada().getRazaoSocial());
        tags.put(Tags.EMPRESA_AVALIADA_ATIVIDADE_PRINCIPAL, laudoData.getEmpresaAvaliada().getAtividadePrincipal());
        tags.put(Tags.EMPRESA_AVALIADA_ENDERECO, "Rua " + laudoData.getEmpresaAvaliada().getEnderecoRua() +
                ", número " + laudoData.getEmpresaAvaliada().getEnderecoNumero() +
                ", bairro " + laudoData.getEmpresaAvaliada().getEnderecoBairro() +
                ", na cidade de " + laudoData.getEmpresaAvaliada().getEnderecoCidade() +
                "/" + laudoData.getEmpresaAvaliada().getEnderecoEstado());
        tags.put(Tags.EMPRESA_AVALIADA_CIDADE, laudoData.getEmpresaAvaliada().getEnderecoCidade());
        tags.put(Tags.EMPRESA_AVALIADA_UF, laudoData.getEmpresaAvaliada().getEnderecoEstado());

        if (laudoData.getEmpresaSolicitante() != null) {
            tags.put(Tags.EMPRESA_SOLICITANTE_CNPJ, laudoData.getEmpresaSolicitante().getDocumento());
            tags.put(Tags.EMPRESA_SOLICITANTE_RZS, laudoData.getEmpresaSolicitante().getRazaoSocial());
            tags.put(Tags.EMPRESA_SOLICITANTE_ATIVIDADE_PRINCIPAL, laudoData.getEmpresaSolicitante().getAtividadePrincipal());
            tags.put(Tags.EMPRESA_SOLICITANTE_ENDERECO, "Rua " + laudoData.getEmpresaSolicitante().getEnderecoRua() +
                    ", número " + laudoData.getEmpresaSolicitante().getEnderecoNumero() +
                    ", bairro " + laudoData.getEmpresaSolicitante().getEnderecoBairro() +
                    ", na cidade de " + laudoData.getEmpresaSolicitante().getEnderecoCidade() +
                    "/" + laudoData.getEmpresaSolicitante().getEnderecoEstado());
            tags.put(Tags.EMPRESA_SOLICITANTE_CIDADE, laudoData.getEmpresaSolicitante().getEnderecoCidade());
            tags.put(Tags.EMPRESA_SOLICITANTE_UF, laudoData.getEmpresaSolicitante().getEnderecoEstado());
        } else {
            tags.put(Tags.EMPRESA_SOLICITANTE_CNPJ, laudoData.getEmpresaAvaliada().getDocumento());
            tags.put(Tags.EMPRESA_SOLICITANTE_RZS, laudoData.getEmpresaAvaliada().getRazaoSocial());
            tags.put(Tags.EMPRESA_SOLICITANTE_ATIVIDADE_PRINCIPAL, laudoData.getEmpresaAvaliada().getAtividadePrincipal());
            tags.put(Tags.EMPRESA_SOLICITANTE_ENDERECO, "Rua " + laudoData.getEmpresaAvaliada().getEnderecoRua() +
                    ", número " + laudoData.getEmpresaAvaliada().getEnderecoNumero() +
                    ", bairro " + laudoData.getEmpresaAvaliada().getEnderecoBairro() +
                    ", na cidade de " + laudoData.getEmpresaAvaliada().getEnderecoCidade() +
                    "/" + laudoData.getEmpresaAvaliada().getEnderecoEstado());
            tags.put(Tags.EMPRESA_SOLICITANTE_CIDADE, laudoData.getEmpresaAvaliada().getEnderecoCidade());
            tags.put(Tags.EMPRESA_SOLICITANTE_UF, laudoData.getEmpresaAvaliada().getEnderecoEstado());
        }

        DateTimeFormatter myFormatObj = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        tags.put(Tags.DATA_INICIO, laudoData.getDataEmissao().format(myFormatObj));
        tags.put(Tags.DATA_CONCLUSAO, laudoData.getDataConclusao().format(myFormatObj));
        tags.put(Tags.CABECALHO_NOME, laudoData.getCabecalho().getNome());
        tags.put(Tags.CABECALHO_RZS, laudoData.getCabecalho().getRazaoSocial());
        tags.put(Tags.CABECALHO_FANTASIA, laudoData.getCabecalho().getFantasia());
        tags.put(Tags.CABECALHO_CIDADE, laudoData.getCabecalho().getEnderecoCidade());
        tags.put(Tags.CABECALHO_UF, laudoData.getCabecalho().getEnderecoEstado());
        tags.put(Tags.DATA_FORMATADA, montarDataFormatada(laudoData, laudoData.getCabecalho()));
        tags.put(Tags.CABECALHO_ENDERECO, "Rua " + laudoData.getCabecalho().getEnderecoRua() +
                ", número " + laudoData.getCabecalho().getEnderecoNumero() +
                ", bairro " + laudoData.getCabecalho().getEnderecoBairro() +
                ", na cidade de " + laudoData.getCabecalho().getEnderecoCidade() +
                "/" + laudoData.getCabecalho().getEnderecoEstado());
        tags.put(Tags.CABECALHO_CNPJ, laudoData.getCabecalho().getCnpj());

        String sugestoesDeMelhoriaLaudo = laudoData.getSugestoesMelhoria();
        if (sugestoesDeMelhoriaLaudo != null && !sugestoesDeMelhoriaLaudo.isEmpty()) {
            tags.put(Tags.SUGESTOES_MELHORIA, sugestoesDeMelhoriaLaudo);
        }

        // Process participants
        JSONArray participantesJson = new JSONArray(laudoData.getParticipantes());
        List<Participante> participantesResponsaveis = new ArrayList<>();
        StringBuilder stringParticipantes = new StringBuilder();
        StringBuilder stringParticipantesResponsaveis = new StringBuilder();
        StringBuilder participantesEmpresaAvaliada = new StringBuilder();
        StringBuilder participantesEmpresaSolicitante = new StringBuilder();
        StringBuilder participantesCabecalho = new StringBuilder();
        StringBuilder responsavelEmpresaAvaliada = new StringBuilder();
        StringBuilder responsavelEmpresaSolicitante = new StringBuilder();
        StringBuilder responsavelEmpresaCabecalho = new StringBuilder();
        String solicitante = "";
        String responsavelAcomapanhamento = "";

        for (int i = 0; i < participantesJson.length(); i++) {
            JSONObject object = participantesJson.getJSONObject(i);
            int tipo = object.getJSONObject("empresa").getInt("value");
            String empresa = switch (tipo) {
                case 1 -> laudoData.getEmpresaSolicitante() != null ?
                        laudoData.getEmpresaSolicitante().getRazaoSocial() :
                        laudoData.getEmpresaAvaliada().getRazaoSocial();
                case 2 -> laudoData.getEmpresaAvaliada().getRazaoSocial();
                case 3 -> laudoData.getCabecalho().getRazaoSocial();
                default -> "";
            };
            Participante participante = new Participante(
                    object.getString("nome"),
                    object.getString("tipo"),
                    object.getBoolean("assina"),
                    object.getString("titulo"),
                    object.getString("documento"),
                    empresa);

            switch (participante.getTipo()) {
                case "Participante" -> {
                    stringParticipantes.append(participante.getNome())
                            .append(", ").append(participante.getTitulo()).append("; ");
                    if (tipo == 1) {
                        participantesEmpresaSolicitante.append(participante.getNome())
                                .append(", ").append(participante.getTitulo()).append("; ");
                    } else if (tipo == 2) {
                        participantesEmpresaAvaliada.append(participante.getNome())
                                .append(", ").append(participante.getTitulo()).append("; ");
                    } else if (tipo == 3) {
                        participantesCabecalho.append(participante.getNome())
                                .append(", ").append(participante.getTitulo()).append("; ");
                    }
                }
                case "Responsável" -> {
                    participantesResponsaveis.add(participante);
                    stringParticipantesResponsaveis.append(participante.getNome())
                            .append(", ").append(participante.getTitulo());
                    if (tipo == 1) {
                        responsavelEmpresaSolicitante.append(participante.getNome())
                                .append(", ").append(participante.getTitulo()).append("; ");
                    } else if (tipo == 2) {
                        responsavelEmpresaAvaliada.append(participante.getNome())
                                .append(", ").append(participante.getTitulo()).append("; ");
                    } else if (tipo == 3) {
                        responsavelEmpresaCabecalho.append(participante.getNome())
                                .append(", ").append(participante.getTitulo()).append("; ");
                    }
                }
                case "Solicitante" -> solicitante = participante.getNome() +
                        ", " + participante.getTitulo() + " da empresa " + empresa;
                case "Responsável acompanhamento" -> responsavelAcomapanhamento =
                        participante.getNome() + ", " + participante.getTitulo() + " da empresa " + empresa;
            }
        }

        tags.put(Tags.EMPRESA_AVALIADA_RESPONSAVEL, responsavelEmpresaAvaliada.toString());
        tags.put(Tags.EMPRESA_SOLICITANTE_RESPONSAVEL, responsavelEmpresaSolicitante.toString());
        tags.put(Tags.CABECALHO_RESPONSAVEL, responsavelEmpresaCabecalho.toString());
        tags.put(Tags.EMPRESA_AVALIADA_PARTICIPANTES, participantesEmpresaAvaliada.toString());
        tags.put(Tags.EMPRESA_SOLICITANTE_PARTICIPANTES, participantesEmpresaSolicitante.toString());
        tags.put(Tags.CABECALHO_PARTICIPANTES, participantesCabecalho.toString());
        tags.put(Tags.EMPRESA_AVALIADA_RESPONSAVEL_ACOMPANHAMENTO, responsavelAcomapanhamento);
        tags.put(Tags.SOLICITANTE, solicitante);
        tags.put(Tags.PARTICIPANTES, stringParticipantes.toString());
        tags.put(Tags.RESPONSAVEIS, stringParticipantesResponsaveis.toString());

        List<ItemModeloLaudo> itensModeloLaudo = this.itemModeloLaudoRepository.buscarItemModelo(laudoData.getModeloLaudo().getId());

        for (ItemModeloLaudo item : itensModeloLaudo) {
            addItensModeloLaudo(mainDocumentPart, wordPackage, item, avaliacoes, tags,
                    laudosAvaliacoes, arts, tocGenerator, participantesResponsaveis);
        }

        if (tags.containsKey(Tags.SUMARIO)) {
            try {
                tocGenerator.updateToc();
            } catch (Exception e) {
                log.error("Erro ao atualizar o sumário: " + e.getMessage());
            }
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Docx4J.save(wordPackage, baos);
            baos.close();
            return baos.toByteArray();
        }
    }

    // ==================== MÉTODOS DE CRIAÇÃO DE TABELAS (CONTINUAÇÃO NO PRÓXIMO BLOCO) ====================

    private String montarListagemArts(List<BigInteger> arts) {
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < arts.size(); i++) {
            if (!(i + 1 == arts.size())) {
                texto.append(arts.get(i)).append(", ");
            } else {
                texto.append(arts.get(i));
            }
        }
        return texto.toString();
    }

    private String montarDataFormatada(Laudo laudo, Cabecalho cabecalho) {
        return cabecalho.getEnderecoCidade() + ", " +
               laudo.getDataConclusao().getDayOfMonth() + " de " +
               getMesPorExtenso(laudo.getDataConclusao().getMonthValue()) + " de " +
               laudo.getDataConclusao().getYear();
    }

    private void addItensModeloLaudo(MainDocumentPart mainDocumentPart,
                                     WordprocessingMLPackage wordPackage,
                                     ItemModeloLaudo item,
                                     List<Avaliacao> avaliacoes,
                                     Map<String, String> tags,
                                     List<LaudoAvaliacoes> laudoAvaliacoes,
                                     List<ArtLaudo> arts,
                                     TocGenerator tocGenerator,
                                     List<Participante> participantesResponsaveis) throws Exception {
        if (item.getPgNova() == '1') {
            addPageBreak(mainDocumentPart);
        }

        String estilo = "Titulo" + item.getNivelTitulo() + "Quebra";
        if (item.getTitulo() != null && !item.isOcultarTitulo()) {
            mainDocumentPart.getContent().add(mainDocumentPart.createStyledParagraphOfText(estilo, item.getTitulo()));
        }

        // Handle special tags
        if (item.getTexto() != null) {
            if (item.getTexto().contains(Tags.TABELA_AVALIACOES)) {
                // Add tables directly to document
                addTabelasItensAvaliados(mainDocumentPart, wordPackage, avaliacoes);
            } else if (item.getTexto().contains(Tags.BIBLIOGRAFIAS)) {
                addListagemDeBibliografias(mainDocumentPart, laudoAvaliacoes);
            } else if (item.getTexto().contains(Tags.ANEXOS)) {
                addListagemAnexos(mainDocumentPart, wordPackage, arts);
            } else if (item.getTexto().contains(Tags.NUMEROS_ART)) {
                String artsText = montarListagemArts(artLaudoRepository.getNumerosArt(item.getIdModeloLaudo().getId()));
                mainDocumentPart.addObject(createSimpleParagraph(artsText));
            } else if (item.getTexto().contains(Tags.EQUIPAMENTOS_AVALIADOS)) {
                addTabelaDePriorizacao(mainDocumentPart, avaliacoes);
            } else if (item.getTexto().contains(Tags.TABELA_HRN)) {
                addTabelaDePriorizacao(mainDocumentPart, avaliacoes);
            } else if (item.getTexto().contains(Tags.ASSINATURA_RESPONSAVEIS)) {
                addAssinaturaResponsaveis(mainDocumentPart, participantesResponsaveis);
            } else if (item.getTexto().contains(Tags.SUMARIO)) {
                Toc.setTocHeadingText("Sumário");
                SdtBlock toc = tocGenerator.generateToc(0, TocHelper.DEFAULT_TOC_INSTRUCTION, false);
                mainDocumentPart.getContent().remove(toc);
                mainDocumentPart.addObject(toc);
            } else {
                // Process regular text with tag substitution
                String processedText = item.getTexto();
                for (Map.Entry<String, String> entry : tags.entrySet()) {
                    processedText = processedText.replace(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
                }
                mainDocumentPart.addObject(createSimpleParagraph(processedText));
            }
        }

        List<ItemModeloLaudo> itens = this.itemModeloLaudoRepository.findItemModeloLaudoByIdItemModeloLaudoOrderByOrdem(item);
        if (!itens.isEmpty()) {
            for (ItemModeloLaudo itemModelo : itens) {
                addItensModeloLaudo(mainDocumentPart, wordPackage, itemModelo, avaliacoes, tags,
                        laudoAvaliacoes, arts, tocGenerator, participantesResponsaveis);
            }
        }
    }

    // ==================== MÉTODOS DE CRIAÇÃO DE TABELAS ====================

    /**
     * Adiciona tabelas de itens avaliados
     */
    private void addTabelasItensAvaliados(MainDocumentPart mainDocumentPart,
                                          WordprocessingMLPackage wordPackage,
                                          List<Avaliacao> avaliacoes) throws Exception {
        for (int i = 0; i < avaliacoes.size(); i++) {
            if (i != 0) {
                addPageBreak(mainDocumentPart);
            }
            if (!avaliacoes.get(i).getStatus()) {
                continue;
            }
            addCabecalhoTabelaAvaliacao(mainDocumentPart, wordPackage, avaliacoes.get(i));
            addTabelaItensAvaliados(mainDocumentPart, avaliacoes.get(i));
            addTabelaPerigosExistentes(mainDocumentPart, avaliacoes.get(i));
            addTabelaSituacoesAdequacao(mainDocumentPart, wordPackage, avaliacoes.get(i));
            addTabelaPrincipalPerigoPotencial(mainDocumentPart, wordPackage, avaliacoes.get(i));
            mainDocumentPart.addObject(factory.createBr());
        }
    }

    /**
     * Adiciona cabeçalho da tabela de avaliação
     */
    private void addCabecalhoTabelaAvaliacao(MainDocumentPart mainDocumentPart,
                                            WordprocessingMLPackage wordPackage,
                                            Avaliacao avaliacao) throws Exception {
        DateTimeFormatter pattern = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String formattedDate = avaliacao.getData().format(pattern);

        String principalImageUrl = avaliacao.getIdItem().getImagens().stream()
                .filter(ImagensMaquinas::isPrincipal)
                .map(ImagensMaquinas::getCaminhoArquivo)
                .findFirst()
                .orElse("https://www2.camara.leg.br/atividade-legislativa/comissoes/comissoes-permanentes/cindra/imagens/sem.jpg.gif/image");

        String principalImageNomeArquivo = avaliacao.getIdItem().getImagens().stream()
                .filter(ImagensMaquinas::isPrincipal)
                .map(ImagensMaquinas::getNomeArquivo)
                .findFirst()
                .orElse("sem.jpg");

        BinaryPartAbstractImage principalImage = null;
        try {
            Path imgPath = imageCache.getImageFile(principalImageUrl, principalImageNomeArquivo);
            if (imgPath != null && Files.exists(imgPath)) {
                principalImage = BinaryPartAbstractImage.createImagePart(wordPackage, imgPath.toFile());
            }
        } catch (Exception e) {
            log.error("Erro ao carregar imagem principal", e);
        }

        Tbl table = factory.createTbl();
        table.setTblPr(createTableProperties(tableWidthPct));

        // Row 1 - Header
        Tr row1 = factory.createTr();
        row1.getContent().add(createTableCell("", 2, 2, false, titleFontSize,
                JcEnumeration.CENTER, null, STVerticalJc.CENTER)); // Logo placeholder
        row1.getContent().add(createTableCell("Auditoria de NR12", 6, 2, true, titleFontSize,
                JcEnumeration.CENTER, null, STVerticalJc.CENTER));
        row1.getContent().add(createTableCell("Data da Avaliação:", 2, 1, false, bodyFontSize,
                JcEnumeration.CENTER, null, STVerticalJc.CENTER));
        row1.getContent().add(createTableCell("", 1, 2, false, bodyFontSize,
                JcEnumeration.CENTER, null, STVerticalJc.CENTER));
        table.getContent().add(row1);

        // Row 2
        Tr row2 = factory.createTr();
        row2.getContent().add(createMergedCell()); // Logo merged
        row2.getContent().add(createMergedCell()); // Logo merged
        row2.getContent().add(createMergedCell()); // Title merged
        row2.getContent().add(createMergedCell());
        row2.getContent().add(createMergedCell());
        row2.getContent().add(createMergedCell());
        row2.getContent().add(createMergedCell());
        row2.getContent().add(createMergedCell());
        row2.getContent().add(createTableCell(formattedDate, 2, 1, false, bodyFontSize,
                JcEnumeration.CENTER, null, STVerticalJc.CENTER));
        row2.getContent().add(createMergedCell());
        table.getContent().add(row2);

        // Row 3 - Image and info header
        Tr row3 = factory.createTr();
        if (principalImage != null) {
            row3.getContent().add(createImageCell(principalImage, wordPackage, 3, 2000000L, 2000000L, null));
        } else {
            row3.getContent().add(createTableCell("", 3, 8, false, bodyFontSize,
                    JcEnumeration.CENTER, null, STVerticalJc.CENTER));
        }
        row3.getContent().add(createTableCell("Informações da máquina / Equipamento", 8, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        table.getContent().add(row3);

        // Rows 4-11 - Machine information
        addMachineInfoRows(table, avaliacao);

        mainDocumentPart.addObject(table);
    }

    /**
     * Adiciona linhas com informações da máquina
     */
    private void addMachineInfoRows(Tbl table, Avaliacao avaliacao) {
        // Row 4
        Tr row4 = factory.createTr();
        row4.getContent().add(createMergedCell());
        row4.getContent().add(createMergedCell());
        row4.getContent().add(createMergedCell());
        row4.getContent().add(createTableCell("Nome da máquina / tipo:", 2, 1, true, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row4.getContent().add(createTableCell(avaliacao.getIdItem().getIdentificacao(), 2, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row4.getContent().add(createTableCell("Nº Série:", 2, 1, true, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row4.getContent().add(createTableCell(avaliacao.getIdItem().getNumeroSerie(), 2, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        table.getContent().add(row4);

        // Row 5
        Tr row5 = factory.createTr();
        row5.getContent().add(createMergedCell());
        row5.getContent().add(createMergedCell());
        row5.getContent().add(createMergedCell());
        row5.getContent().add(createTableCell("N° Patrimônio /TAG:", 2, 1, true, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row5.getContent().add(createTableCell(avaliacao.getIdItem().getPatrimonio(), 2, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row5.getContent().add(createTableCell("Fabricação:", 2, 1, true, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row5.getContent().add(createTableCell(String.valueOf(avaliacao.getIdItem().getAnoFabricacao()), 2, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        table.getContent().add(row5);

        // Row 6
        Tr row6 = factory.createTr();
        row6.getContent().add(createMergedCell());
        row6.getContent().add(createMergedCell());
        row6.getContent().add(createMergedCell());
        row6.getContent().add(createTableCell("Localização / Setor:", 2, 1, true, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row6.getContent().add(createTableCell(avaliacao.getIdSetor().getIdentificacao(), 2, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row6.getContent().add(createTableCell("Capacidade:", 2, 1, true, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row6.getContent().add(createTableCell(avaliacao.getIdItem().getCapacidade(), 2, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        table.getContent().add(row6);

        // Row 7
        Tr row7 = factory.createTr();
        row7.getContent().add(createMergedCell());
        row7.getContent().add(createMergedCell());
        row7.getContent().add(createMergedCell());
        row7.getContent().add(createTableCell("Modelo:", 2, 1, true, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row7.getContent().add(createTableCell(avaliacao.getIdItem().getModelo(), 2, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row7.getContent().add(createTableCell("Peso:", 2, 1, true, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        String peso = (avaliacao.getIdItem().getPeso() != null) ?
                avaliacao.getIdItem().getPeso() + " " + TipoPeso.fromKey(avaliacao.getIdItem().getTipoPeso()) : "NI";
        row7.getContent().add(createTableCell(peso, 2, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        table.getContent().add(row7);

        // Row 8
        Tr row8 = factory.createTr();
        row8.getContent().add(createMergedCell());
        row8.getContent().add(createMergedCell());
        row8.getContent().add(createMergedCell());
        row8.getContent().add(createTableCell("Fabricante:", 2, 1, true, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row8.getContent().add(createTableCell(avaliacao.getIdItem().getFabricante(), 2, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row8.getContent().add(createTableCell("CREA Fabricante:", 2, 1, true, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row8.getContent().add(createTableCell(avaliacao.getIdItem().getDocFabricante(), 2, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        table.getContent().add(row8);

        // Row 9 - Description header
        Tr row9 = factory.createTr();
        row9.getContent().add(createMergedCell());
        row9.getContent().add(createMergedCell());
        row9.getContent().add(createMergedCell());
        row9.getContent().add(createTableCell("Breve descrição da Operação / Limitação do trabalho", 8, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        table.getContent().add(row9);

        // Row 10 - Description content
        Tr row10 = factory.createTr();
        row10.getContent().add(createMergedCell());
        row10.getContent().add(createMergedCell());
        row10.getContent().add(createMergedCell());
        row10.getContent().add(createTableCell(avaliacao.getIdItem().getIdTipoItem().getIdentificacao(), 8, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        table.getContent().add(row10);
    }

    /**
     * Adiciona tabela de itens avaliados
     */
    private void addTabelaItensAvaliados(MainDocumentPart mainDocumentPart, Avaliacao avaliacao) {
        Tbl table = factory.createTbl();
        table.setTblPr(createTableProperties(tableWidthPct));

        // Header row
        Tr headerRow = factory.createTr();
        headerRow.getContent().add(createTableCell("Itens Avaliados", 6, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow.getContent().add(createTableCell("Status", 1, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow.getContent().add(createTableCell("Itens Avaliados", 6, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow.getContent().add(createTableCell("Status", 1, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        table.getContent().add(headerRow);

        List<GrupoCriterio> gruposCriterioAvaliados = this.grupoCriterioRepository
                .findGrupoCriterioInAvaliacaoByIdAvaliacaoInCriterioAvaliado(avaliacao.getId());

        double porcentagemTotalAtendimento = 0;
        int totalLines = this.criterioAvaliadoRepository.countLinesInItensAvaliadosTable(avaliacao.getId()) + 1;
        int midleTable = divideTableSize(totalLines);
        ArrayList<TableDataRow> tableData = new ArrayList<>();

        for (GrupoCriterio grupoCriterio : gruposCriterioAvaliados) {
            GrupoCriterioAvaliadoDTO grupoCriterioDTO = new GrupoCriterioAvaliadoDTO();
            grupoCriterioDTO.setIdentificacao(grupoCriterio.getIdentificacao());
            grupoCriterioDTO.setCor(grupoCriterio.getCor());

            List<CriterioAvaliado> criterioAvaliados = this.criterioAvaliadoRepository
                    .findByIdGrupoCriterio(grupoCriterio.getId(), avaliacao.getId())
                    .orElseThrow(() -> new NotFoundException("GrupoCriterio not found with id " + grupoCriterio.getId()));

            for (CriterioAvaliado criterioAvaliado : criterioAvaliados) {
                CriterioAvaliadoDTO criterioAvaliadoDTO = new CriterioAvaliadoDTO();
                criterioAvaliadoDTO.setCriterio(criterioAvaliado.getCriterio());
                criterioAvaliadoDTO.setStatus(criterioAvaliado.getStatus());
                criterioAvaliadoDTO.setSituacaoAdequacaoCriterioAvaliados(criterioAvaliado.getSituacoesAdequacao());
                grupoCriterioDTO.getCriterios().add(criterioAvaliadoDTO);

                String lighterColor = lightenColor(grupoCriterioDTO.getCor());
                String statusColor = criterioAvaliado.getStatus().getCor();

                tableData.add(new TableDataRow(
                        criterioAvaliado.getItem() + "- " + criterioAvaliado.getCriterio().getDescricao(),
                        hexToColor(lighterColor),
                        criterioAvaliado.getStatus().getIdentificacao(),
                        hexToColor(statusColor)
                ));
            }

            var porcentagemDoGrupo = roundAvoid(calcularAtendimentoGrupo(grupoCriterioDTO), 2);
            porcentagemTotalAtendimento += porcentagemDoGrupo;

            tableData.add(new TableDataRow(
                    "% Atendimento " + grupoCriterioDTO.getIdentificacao(),
                    "00FF00",
                    porcentagemDoGrupo + "%",
                    "00FF00"
            ));
        }

        porcentagemTotalAtendimento = porcentagemTotalAtendimento / gruposCriterioAvaliados.size();
        tableData.add(new TableDataRow(
                "% Geral de atendimento NR12",
                "00FF00",
                roundAvoid(porcentagemTotalAtendimento, 2) + "%",
                "00FF00"
        ));

        // Add data rows
        for (int line = 0; line < midleTable; line++) {
            Tr row = factory.createTr();
            TableDataRow leftData = tableData.get(line);
            row.getContent().add(createTableCell(leftData.text, 6, 1, false, bodyFontSize,
                    JcEnumeration.LEFT, leftData.bgColor, STVerticalJc.CENTER));
            row.getContent().add(createTableCell(leftData.status, 1, 1, true, bodyFontSize,
                    JcEnumeration.CENTER, leftData.statusColor, STVerticalJc.CENTER));

            if (midleTable + line < tableData.size()) {
                TableDataRow rightData = tableData.get(midleTable + line);
                row.getContent().add(createTableCell(rightData.text, 6, 1, false, bodyFontSize,
                        JcEnumeration.LEFT, rightData.bgColor, STVerticalJc.CENTER));
                row.getContent().add(createTableCell(rightData.status, 1, 1, true, bodyFontSize,
                        JcEnumeration.CENTER, rightData.statusColor, STVerticalJc.CENTER));
            } else {
                row.getContent().add(createTableCell("", 7, 1, false, bodyFontSize,
                        JcEnumeration.LEFT, null, STVerticalJc.CENTER));
            }
            table.getContent().add(row);
        }

        mainDocumentPart.addObject(table);
    }

    // Helper classes for table data
    private static class TableDataRow {
        String text;
        String bgColor;
        String status;
        String statusColor;

        TableDataRow(String text, String bgColor, String status, String statusColor) {
            this.text = text;
            this.bgColor = bgColor;
            this.status = status;
            this.statusColor = statusColor;
        }
    }

    private static class AvaliacoesToSortDTO {
        String setor;
        String maquina;
        String patrimonio;
        String numeroSerie;
        double hrn;
        double atendimentoPrecent;

        AvaliacoesToSortDTO(String setor, String maquina, String patrimonio, String numeroSerie, double hrn, double atendimentoPrecent) {
            this.setor = setor;
            this.maquina = maquina;
            this.patrimonio = patrimonio;
            this.numeroSerie = numeroSerie;
            this.hrn = hrn;
            this.atendimentoPrecent = atendimentoPrecent;
        }

        String getSetor() { return setor; }
        String getMaquina() { return maquina; }
        String getPatrimonio() { return patrimonio; }
        String getNumeroSerie() { return numeroSerie; }
        double getHrn() { return hrn; }
        double getAtendimentoPrecent() { return atendimentoPrecent; }
    }

    /**
     * Adiciona tabela de perigos existentes
     */
    private void addTabelaPerigosExistentes(MainDocumentPart mainDocumentPart, Avaliacao avaliacao) {
        if (!avaliacao.getStatus()) {
            return;
        }

        List<Long> idsGruposPerigoAvaliacao = grupoPerigoRepository.findAll().stream()
                .map(GrupoPerigo::getId).toList();
        List<GrupoPerigoRelatorioDTO> gruposPerigoDTO = new ArrayList<>();

        for (Long id : idsGruposPerigoAvaliacao) {
            List<Perigo> perigosDoGrupo = perigoRepository.buscarPerigosDaAvaliacao(avaliacao.getId(), id);
            GrupoPerigo grupoPerigo = this.grupoPerigoRepository.findById(id).orElse(null);
            if (grupoPerigo != null) {
                gruposPerigoDTO.add(new GrupoPerigoRelatorioDTO(grupoPerigo, perigosDoGrupo));
            }
        }

        int maiorGrupo = 0;
        for (GrupoPerigoRelatorioDTO grupo : gruposPerigoDTO) {
            if (!grupo.getPerigosDoGrupo().isEmpty() && grupo.getPerigosDoGrupo().size() > maiorGrupo) {
                maiorGrupo = grupo.getPerigosDoGrupo().size();
            }
        }

        if (maiorGrupo == 0) {
            return;
        }

        Tbl table = factory.createTbl();
        table.setTblPr(createTableProperties(tableWidthPct));

        // Header
        Tr headerRow = factory.createTr();
        headerRow.getContent().add(createTableCell("Perigos Existentes: (NBR ISO 12100)",
                7 + (7 * maiorGrupo), 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        table.getContent().add(headerRow);

        // Data rows
        for (int i = 0; i < gruposPerigoDTO.size(); i++) {
            GrupoPerigoRelatorioDTO grupo = gruposPerigoDTO.get(i);
            Tr row = factory.createTr();

            row.getContent().add(createTableCell(String.valueOf(i + 1), 2, 1, false, bodyFontSize,
                    JcEnumeration.CENTER, "F5F5F5", STVerticalJc.CENTER));
            row.getContent().add(createTableCell(grupo.getGrupoPerigo().getIdentificacao(), 5, 1, false, bodyFontSize,
                    JcEnumeration.CENTER, null, STVerticalJc.CENTER));

            if (grupo.getPerigosDoGrupo().isEmpty()) {
                row.getContent().add(createTableCell("Não identificado", 7 * maiorGrupo, 1, false, bodyFontSize,
                        JcEnumeration.LEFT, null, STVerticalJc.CENTER));
            } else {
                for (int j = 0; j < maiorGrupo; j++) {
                    if (j < grupo.getPerigosDoGrupo().size()) {
                        Perigo perigo = grupo.getPerigosDoGrupo().get(j);
                        row.getContent().add(createTableCell(perigo.getItem() + "." + perigo.getSubItem(),
                                2, 1, false, bodyFontSize, JcEnumeration.CENTER, "F5F5F5", STVerticalJc.CENTER));
                        row.getContent().add(createTableCell(perigo.getORIGEM(), 5, 1, false, bodyFontSize,
                                JcEnumeration.CENTER, null, STVerticalJc.CENTER));
                    } else {
                        row.getContent().add(createTableCell("x", 2, 1, false, bodyFontSize,
                                JcEnumeration.CENTER, "F5F5F5", STVerticalJc.CENTER));
                        row.getContent().add(createTableCell("x", 5, 1, false, bodyFontSize,
                                JcEnumeration.CENTER, null, STVerticalJc.CENTER));
                    }
                }
            }
            table.getContent().add(row);
        }

        mainDocumentPart.addObject(table);
    }

    /**
     * Adiciona tabela de situações de adequação
     */
    private void addTabelaSituacoesAdequacao(MainDocumentPart mainDocumentPart,
                                            WordprocessingMLPackage wordPackage,
                                            Avaliacao avaliacao) throws Exception {
        List<SituacaoAdequacaoCriterioAvaliado> situacaoAdequacaoCriterioAvaliados =
                this.situacaoAdequacaoCriterioAvaliadoRepository.findByIdAvaliacao(avaliacao.getId());

        boolean hasCheckedItems = situacaoAdequacaoCriterioAvaliados.stream()
                .anyMatch(SituacaoAdequacaoCriterioAvaliado::isChecked);

        if (!hasCheckedItems || situacaoAdequacaoCriterioAvaliados.isEmpty()) {
            return;
        }

        situacaoAdequacaoCriterioAvaliados.sort(Comparator.comparingInt(o -> o.getCriterioAvaliado().getItem()));

        Tbl table = factory.createTbl();
        table.setTblPr(createTableProperties(tableWidthPct));

        // Header rows
        Tr headerRow1 = factory.createTr();
        headerRow1.getContent().add(createTableCell("Avaliação Qualitativa do Perigo Existente", 18, 1,
                true, subTitleFontSize, JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        table.getContent().add(headerRow1);

        Tr headerRow2 = factory.createTr();
        headerRow2.getContent().add(createTableCell("N° Item", 1, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow2.getContent().add(createTableCell("Registro fotográfico", 3, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow2.getContent().add(createTableCell("Situação Identificada", 7, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow2.getContent().add(createTableCell("Sugestão de adequação", 7, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        table.getContent().add(headerRow2);

        // Data rows
        for (SituacaoAdequacaoCriterioAvaliado situacao : situacaoAdequacaoCriterioAvaliados) {
            if (!situacao.isChecked()) {
                continue;
            }

            Tr row = factory.createTr();

            // Item number
            row.getContent().add(createTableCell(
                    situacao.getCriterioAvaliado().getItem() + "." + situacao.getSituacaoAdequacao().getSubitem(),
                    1, 1, false, bodyFontSize, JcEnumeration.CENTER, null, STVerticalJc.CENTER));

            // Image
            BinaryPartAbstractImage image = null;
            if (situacao.getCaminhoArquivo() != null && !situacao.getCaminhoArquivo().isBlank()) {
                try {
                    Path imgPath = imageCache.getImageFile(situacao.getCaminhoArquivo(), situacao.getNomeArquivo());
                    if (imgPath != null && Files.exists(imgPath)) {
                        image = BinaryPartAbstractImage.createImagePart(wordPackage, imgPath.toFile());
                    }
                } catch (Exception e) {
                    log.error("Erro ao carregar imagem de situação", e);
                }
            }

            if (image != null) {
                row.getContent().add(createImageCell(image, wordPackage, 3, 1500000L, 1500000L, null));
            } else {
                row.getContent().add(createTableCell("N/A", 3, 1, false, bodyFontSize,
                        JcEnumeration.CENTER, null, STVerticalJc.CENTER));
            }

            // Situation
            row.getContent().add(createTableCell(situacao.getSituacaoAdequacao().getSituacao(), 7, 1,
                    false, bodyFontSize, JcEnumeration.LEFT, null, STVerticalJc.CENTER));

            // Adequacy suggestion
            row.getContent().add(createTableCell(situacao.getSituacaoAdequacao().getAdequacao(), 7, 1,
                    false, bodyFontSize, JcEnumeration.LEFT, null, STVerticalJc.CENTER));

            table.getContent().add(row);
        }

        mainDocumentPart.addObject(table);
    }

    /**
     * Adiciona tabela principal de perigo potencial
     */
    private void addTabelaPrincipalPerigoPotencial(MainDocumentPart mainDocumentPart,
                                                  WordprocessingMLPackage wordPackage,
                                                  Avaliacao avaliacao) throws Exception {
        Optional<PiorSituacaoAvaliacao> principalPerigoPotencial =
                piorSituacaoRepository.findByIdAvaliacao(avaliacao.getId());

        if (principalPerigoPotencial.isEmpty()) {
            return;
        }

        PiorSituacaoAvaliacao piorSituacao = principalPerigoPotencial.get();
        double hrn = piorSituacao.getIdProbabilidadeOcorrenciaDano().getValue() *
                     piorSituacao.getIdServeridadeDano().getValue() *
                     piorSituacao.getIdFrequenciaExposicaoRisco().getValue() *
                     piorSituacao.getQntdPessoasExpostas().getValue();

        String observacao = piorSituacao.getSituacaoAdequacaoCriterioAvaliado().getObservacao() != null ?
                piorSituacao.getSituacaoAdequacaoCriterioAvaliado().getObservacao() : "";

        HrnStatusDTO status = getHrnStatus(hrn);

        Tbl table = factory.createTbl();
        table.setTblPr(createTableProperties(tableWidthPct));

        // Row 1 - Description
        Tr row1 = factory.createTr();
        row1.getContent().add(createTableCell("Descrição do principal perigo potencial:", 6, 1,
                true, bodyFontSize, JcEnumeration.LEFT, "DCDCDC", STVerticalJc.CENTER));
        row1.getContent().add(createTableCell(
                piorSituacao.getSituacaoAdequacaoCriterioAvaliado().getSituacaoAdequacao().getSituacao(),
                12, 1, false, bodyFontSize, JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        table.getContent().add(row1);

        // Row 2
        Tr row2 = factory.createTr();
        row2.getContent().add(createTableCell("Avaliação Quantitativa (HRN)", 2, 4, true, bodyFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        row2.getContent().add(createTableCell("Probabilidade de Ocorrência Dano (Pr)", 4, 1,
                false, bodyFontSize, JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row2.getContent().add(createTableCell(String.valueOf(piorSituacao.getIdProbabilidadeOcorrenciaDano().getValue()),
                1, 1, false, bodyFontSize, JcEnumeration.CENTER, "F5F5F5", STVerticalJc.CENTER));
        row2.getContent().add(createTableCell(piorSituacao.getIdProbabilidadeOcorrenciaDano().getLabel(),
                5, 1, false, bodyFontSize, JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row2.getContent().add(createTableCell("HRN = Pr x Se x Fr x NP", 6, 1, true, bodyFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        table.getContent().add(row2);

        // Row 3
        Tr row3 = factory.createTr();
        row3.getContent().add(createMergedCell());
        row3.getContent().add(createMergedCell());
        row3.getContent().add(createTableCell("Severidade de Dano (Se)", 4, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row3.getContent().add(createTableCell(String.valueOf(piorSituacao.getIdServeridadeDano().getValue()),
                1, 1, false, bodyFontSize, JcEnumeration.CENTER, "F5F5F5", STVerticalJc.CENTER));
        row3.getContent().add(createTableCell(piorSituacao.getIdServeridadeDano().getLabel(), 5, 1,
                false, bodyFontSize, JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row3.getContent().add(createTableCell("HRN=", 3, 1, true, bodyFontSize,
                JcEnumeration.RIGHT, "DCDCDC", STVerticalJc.CENTER));
        row3.getContent().add(createTableCell(String.valueOf(hrn), 3, 1, true, bodyFontSize,
                JcEnumeration.CENTER, hexToColor(status.getCor()), STVerticalJc.CENTER));
        table.getContent().add(row3);

        // Row 4
        Tr row4 = factory.createTr();
        row4.getContent().add(createMergedCell());
        row4.getContent().add(createMergedCell());
        row4.getContent().add(createTableCell("Frequência Exposição risco (Fr)", 4, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row4.getContent().add(createTableCell(String.valueOf(piorSituacao.getIdFrequenciaExposicaoRisco().getValue()),
                1, 1, false, bodyFontSize, JcEnumeration.CENTER, "F5F5F5", STVerticalJc.CENTER));
        row4.getContent().add(createTableCell(piorSituacao.getIdFrequenciaExposicaoRisco().getLabel(), 5, 1,
                false, bodyFontSize, JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row4.getContent().add(createTableCell("Classificação do risco:", 3, 1, true, bodyFontSize,
                JcEnumeration.RIGHT, "DCDCDC", STVerticalJc.CENTER));
        row4.getContent().add(createTableCell(status.getRisco(), 3, 1, true, bodyFontSize,
                JcEnumeration.CENTER, hexToColor(status.getCor()), STVerticalJc.CENTER));
        table.getContent().add(row4);

        // Row 5
        Tr row5 = factory.createTr();
        row5.getContent().add(createMergedCell());
        row5.getContent().add(createMergedCell());
        row5.getContent().add(createTableCell("N° pessoas Expostas (NP)", 4, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row5.getContent().add(createTableCell(String.valueOf(piorSituacao.getQntdPessoasExpostas().getValue()),
                1, 1, false, bodyFontSize, JcEnumeration.CENTER, "F5F5F5", STVerticalJc.CENTER));
        row5.getContent().add(createTableCell(piorSituacao.getQntdPessoasExpostas().getLabel(), 5, 1,
                false, bodyFontSize, JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        row5.getContent().add(createTableCell("Ação:", 3, 1, true, bodyFontSize,
                JcEnumeration.RIGHT, "DCDCDC", STVerticalJc.CENTER));
        row5.getContent().add(createTableCell(status.getAcao(), 3, 1, true, bodyFontSize,
                JcEnumeration.CENTER, hexToColor(status.getCor()), STVerticalJc.CENTER));
        table.getContent().add(row5);

        // Row 6 - Headers for photo and observations
        Tr row6 = factory.createTr();
        row6.getContent().add(createTableCell("Registro Fotográfico do Principal Perigo Potencial", 9, 1,
                true, bodyFontSize, JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        row6.getContent().add(createTableCell("Observações", 9, 1, true, bodyFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        table.getContent().add(row6);

        // Row 7 - Photo and observations content
        Tr row7 = factory.createTr();

        // Image
        BinaryPartAbstractImage image = null;
        String caminhoArquivo = piorSituacao.getSituacaoAdequacaoCriterioAvaliado().getCaminhoArquivo();
        if (caminhoArquivo != null && !caminhoArquivo.isBlank()) {
            try {
                Path imgPath = imageCache.getImageFile(caminhoArquivo,
                        piorSituacao.getSituacaoAdequacaoCriterioAvaliado().getNomeArquivo());
                if (imgPath != null && Files.exists(imgPath)) {
                    image = BinaryPartAbstractImage.createImagePart(wordPackage, imgPath.toFile());
                }
            } catch (Exception e) {
                log.error("Erro ao carregar imagem principal de perigo", e);
            }
        }

        if (image != null) {
            row7.getContent().add(createImageCell(image, wordPackage, 9, 1500000L, 1500000L, null));
        } else {
            row7.getContent().add(createTableCell("N/A", 9, 1, false, bodyFontSize,
                    JcEnumeration.CENTER, null, STVerticalJc.CENTER));
        }

        row7.getContent().add(createTableCell(observacao, 9, 1, false, bodyFontSize,
                JcEnumeration.LEFT, null, STVerticalJc.CENTER));
        table.getContent().add(row7);

        mainDocumentPart.addObject(table);
    }

    /**
     * Adiciona tabela de priorização
     */
    private void addTabelaDePriorizacao(MainDocumentPart mainDocumentPart, List<Avaliacao> avaliacoes) {
        List<AvaliacoesToSortDTO> linhaAvalicoes = new ArrayList<>();

        for (Avaliacao avaliacao : avaliacoes) {
            if (!avaliacao.getStatus()) {
                continue;
            }

            double geralAtendimento = roundAvoid(calcularGeralAtendiment(avaliacao.getId()), 2);
            Optional<PiorSituacaoAvaliacao> principalPerigoPotencial =
                    piorSituacaoRepository.findByIdAvaliacao(avaliacao.getId());

            double hrn = 0;
            if (principalPerigoPotencial.isPresent()) {
                PiorSituacaoAvaliacao pior = principalPerigoPotencial.get();
                hrn = pior.getIdProbabilidadeOcorrenciaDano().getValue() *
                      pior.getIdServeridadeDano().getValue() *
                      pior.getIdFrequenciaExposicaoRisco().getValue() *
                      pior.getQntdPessoasExpostas().getValue();
            }

            linhaAvalicoes.add(new AvaliacoesToSortDTO(
                    avaliacao.getIdSetor().getIdentificacao(),
                    avaliacao.getIdItem().getIdentificacao(),
                    avaliacao.getIdItem().getPatrimonio(),
                    avaliacao.getIdItem().getNumeroSerie(),
                    hrn,
                    geralAtendimento
            ));
        }

        linhaAvalicoes.sort(Comparator.comparingDouble(AvaliacoesToSortDTO::getAtendimentoPrecent));

        Tbl table = factory.createTbl();
        table.setTblPr(createTableProperties(tableWidthPct));

        // Header
        Tr headerRow = factory.createTr();
        headerRow.getContent().add(createTableCell("Prior.", 1, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow.getContent().add(createTableCell("Localização/Setor", 1, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow.getContent().add(createTableCell("Máquina", 1, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow.getContent().add(createTableCell("Nº Patrimônio", 1, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow.getContent().add(createTableCell("Nº Série", 1, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow.getContent().add(createTableCell("HRN", 1, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        headerRow.getContent().add(createTableCell("% Atendimento NR 12", 1, 1, true, subTitleFontSize,
                JcEnumeration.CENTER, "DCDCDC", STVerticalJc.CENTER));
        table.getContent().add(headerRow);

        // Data rows
        for (int i = 0; i < linhaAvalicoes.size(); i++) {
            AvaliacoesToSortDTO data = linhaAvalicoes.get(i);
            Tr row = factory.createTr();
            row.getContent().add(createTableCell((i + 1) + "°", 1, 1, false, bodyFontSize,
                    JcEnumeration.CENTER, null, STVerticalJc.CENTER));
            row.getContent().add(createTableCell(data.getSetor(), 1, 1, false, bodyFontSize,
                    JcEnumeration.LEFT, null, STVerticalJc.CENTER));
            row.getContent().add(createTableCell(data.getMaquina(), 1, 1, false, bodyFontSize,
                    JcEnumeration.LEFT, null, STVerticalJc.CENTER));
            row.getContent().add(createTableCell(data.getPatrimonio(), 1, 1, false, bodyFontSize,
                    JcEnumeration.CENTER, null, STVerticalJc.CENTER));
            row.getContent().add(createTableCell(data.getNumeroSerie(), 1, 1, false, bodyFontSize,
                    JcEnumeration.CENTER, null, STVerticalJc.CENTER));
            row.getContent().add(createTableCell(String.valueOf(data.getHrn()), 1, 1, false, bodyFontSize,
                    JcEnumeration.CENTER, null, STVerticalJc.CENTER));
            row.getContent().add(createTableCell(data.getAtendimentoPrecent() + "%", 1, 1, false, bodyFontSize,
                    JcEnumeration.CENTER, null, STVerticalJc.CENTER));
            table.getContent().add(row);
        }

        mainDocumentPart.addObject(table);
    }

    /**
     * Adiciona listagem de anexos
     */
    private void addListagemAnexos(MainDocumentPart mainDocumentPart,
                                  WordprocessingMLPackage wordPackage,
                                  List<ArtLaudo> arts) throws Exception {
        for (ArtLaudo art : arts) {
            // Título
            P titleP = factory.createP();
            String titulo = !Objects.equals(art.getArtNumero(), BigInteger.ZERO) ?
                    art.getIdAnexo().getTitulo() + " - " + art.getArtNumero() :
                    art.getIdAnexo().getTitulo();
            addRunToParagraph(titleP, titulo, false, bodyFontSize);
            mainDocumentPart.addObject(titleP);

            // Imagem do anexo
            String anexoCaminho = art.getIdAnexo().getCaminhoArquivo();
            if (anexoCaminho != null && !anexoCaminho.isBlank()) {
                try {
                    Path imgPath = imageCache.getImageFile(anexoCaminho, art.getIdAnexo().getNomeArquivo());
                    if (imgPath != null && Files.exists(imgPath)) {
                        BinaryPartAbstractImage image = BinaryPartAbstractImage.createImagePart(
                                wordPackage, imgPath.toFile());

                        P imgP = factory.createP();
                        Inline inline = image.createImageInline("Anexo", "Anexo", 1, 2, false);

                        // Ajustar tamanho da imagem
                        PageDimensions page = wordPackage.getDocumentModel().getSections().get(0).getPageDimensions();
                        long widthEmu = page.getWritableWidthTwips() * 635L;
                        long heightEmu = 4000000L; // Altura padrão

                        inline.getExtent().setCx(widthEmu);
                        inline.getExtent().setCy(heightEmu);

                        if (inline.getGraphic() != null) {
                            inline.getGraphic().getGraphicData().getPic().getSpPr().getXfrm().getExt().setCx(widthEmu);
                            inline.getGraphic().getGraphicData().getPic().getSpPr().getXfrm().getExt().setCy(heightEmu);
                        }

                        R run = factory.createR();
                        Drawing drawing = factory.createDrawing();
                        drawing.getAnchorOrInline().add(inline);
                        run.getContent().add(drawing);
                        imgP.getContent().add(run);
                        mainDocumentPart.addObject(imgP);
                    }
                } catch (Exception e) {
                    log.error("Erro ao carregar imagem de anexo", e);
                    mainDocumentPart.addObject(createSimpleParagraph(""));
                }
            }
        }
    }

    /**
     * Adiciona assinatura de responsáveis
     */
    private void addAssinaturaResponsaveis(MainDocumentPart mainDocumentPart,
                                          List<Participante> responsaveis) {
        responsaveis.stream()
                .filter(Participante::isAssina)
                .forEach(resp -> {
                    // Linha de assinatura
                    P lineP = createParagraph("__________________________________________________",
                            false, bodyFontSize, JcEnumeration.CENTER, null);
                    mainDocumentPart.addObject(lineP);

                    // Nome em negrito
                    P nameP = createParagraph(resp.getNome(), true, bodyFontSize,
                            JcEnumeration.CENTER, null);
                    mainDocumentPart.addObject(nameP);

                    // Título
                    P titleP = createParagraph(resp.getTitulo(), false, bodyFontSize,
                            JcEnumeration.CENTER, null);
                    mainDocumentPart.addObject(titleP);

                    // Documento
                    P docP = createParagraph(resp.getDocumento(), false, bodyFontSize,
                            JcEnumeration.CENTER, null);
                    mainDocumentPart.addObject(docP);

                    // Espaço
                    mainDocumentPart.addObject(factory.createP());
                });
    }

    /**
     * Adiciona listagem de bibliografias
     */
    private void addListagemDeBibliografias(MainDocumentPart mainDocumentPart,
                                           List<LaudoAvaliacoes> avaliacoeDoLaudo) {
        for (LaudoAvaliacoes laudoAvaliacoes : avaliacoeDoLaudo) {
            List<FuncaoItemBibliografia> bibliografias = funcaoItemBibliografiaRepository
                    .findByIdFuncaoItem(laudoAvaliacoes.getIdAvaliacao().getIdItem().getFuncaoItem());

            for (FuncaoItemBibliografia bibliografia : bibliografias) {
                P p = factory.createP();
                addRunToParagraph(p, bibliografia.getIdBibliografia().getIDENTIFICACAO() + ": ",
                        true, bodyFontSize);
                addRunToParagraph(p, bibliografia.getIdBibliografia().getDESCRICAO(),
                        false, bodyFontSize);
                mainDocumentPart.addObject(p);
            }
        }
    }

    // ==================== MÉTODOS AUXILIARES ====================

    private static void addPageBreak(MainDocumentPart documentPart) {
        P p = Context.getWmlObjectFactory().createP();
        R r = Context.getWmlObjectFactory().createR();
        p.getContent().add(r);
        Br br = Context.getWmlObjectFactory().createBr();
        r.getContent().add(br);
        br.setType(STBrType.PAGE);
        documentPart.addObject(p);
    }

    int divideTableSize(int totalLines) {
        if (totalLines % 2 == 0) {
            return totalLines / 2;
        } else {
            return (totalLines + 1) / 2;
        }
    }

    private double calcularGeralAtendiment(Long idAvaliacao) {
        double porcentagemTotalAtendimento = 0;
        List<GrupoCriterio> gruposCriterioAvaliados = this.grupoCriterioRepository
                .findGrupoCriterioInAvaliacaoByIdAvaliacaoInCriterioAvaliado(idAvaliacao);

        for (GrupoCriterio grupoCriterio : gruposCriterioAvaliados) {
            GrupoCriterioAvaliadoDTO grupoCriterioDTO = new GrupoCriterioAvaliadoDTO();
            grupoCriterioDTO.setIdentificacao(grupoCriterio.getIdentificacao());
            grupoCriterioDTO.setCor(grupoCriterio.getCor());

            List<CriterioAvaliado> criterioAvaliados = this.criterioAvaliadoRepository
                    .findByIdGrupoCriterio(grupoCriterio.getId(), idAvaliacao)
                    .orElseThrow(() -> new NotFoundException("GrupoCriterio not found with id " + grupoCriterio.getId()));

            for (CriterioAvaliado criterioAvaliado : criterioAvaliados) {
                CriterioAvaliadoDTO criterioAvaliadoDTO = new CriterioAvaliadoDTO();
                criterioAvaliadoDTO.setCriterio(criterioAvaliado.getCriterio());
                criterioAvaliadoDTO.setStatus(criterioAvaliado.getStatus());
                criterioAvaliadoDTO.setSituacaoAdequacaoCriterioAvaliados(criterioAvaliado.getSituacoesAdequacao());
                grupoCriterioDTO.getCriterios().add(criterioAvaliadoDTO);
            }
            var porcentagemDoGrupo = roundAvoid(calcularAtendimentoGrupo(grupoCriterioDTO), 2);
            porcentagemTotalAtendimento += porcentagemDoGrupo;
        }
        porcentagemTotalAtendimento = porcentagemTotalAtendimento / gruposCriterioAvaliados.size();
        return porcentagemTotalAtendimento;
    }

    private double calcularAtendimentoGrupo(GrupoCriterioAvaliadoDTO grupoCriterioDTO) {
        var total = !grupoCriterioDTO.getCriterios().isEmpty() ? grupoCriterioDTO.getCriterios().size() : 100;
        double adequados = 0;
        double porcentagem = 0;

        for (CriterioAvaliadoDTO criterioAvaliadoDTO : grupoCriterioDTO.getCriterios()) {
            Long idStatus;
            if (criterioAvaliadoDTO.getStatus() == null) {
                continue;
            }
            idStatus = criterioAvaliadoDTO.getStatus().getId();
            if (idStatus == 4L) {
                total -= 1;
            } else if (idStatus == 1L) {
                adequados += 1;
            } else if (idStatus == 3L) {
                adequados += .5;
            }
        }

        if (total != 0) {
            porcentagem = (adequados / total) * 100;
        }
        return porcentagem;
    }

    private HrnStatusDTO getHrnStatus(double hrn) {
        HrnStatusDTO response = new HrnStatusDTO();
        if (hrn >= 0 && hrn <= 5) {
            response.setAcao("Nenhuma ação necessária");
            response.setRisco("Insignificante");
            response.setCor("green");
        } else if (hrn > 5 && hrn <= 50) {
            response.setAcao("Melhoria Recomendada");
            response.setRisco("Baixo porém significativo");
            response.setCor("yellow");
        } else if (hrn > 50 && hrn <= 500) {
            response.setAcao("Necessária Ação de Melhoria");
            response.setRisco("Alto");
            response.setCor("orange");
        } else if (hrn > 500) {
            response.setAcao("Necessária Ação de Melhoria");
            response.setRisco("Inaceitável");
            response.setCor("red");
        }
        return response;
    }

    public static double roundAvoid(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    private String getMesPorExtenso(int mes) {
        return switch (mes) {
            case 1 -> "Janeiro";
            case 2 -> "Fevereiro";
            case 3 -> "Março";
            case 4 -> "Abril";
            case 5 -> "Maio";
            case 6 -> "Junho";
            case 7 -> "Julho";
            case 8 -> "Agosto";
            case 9 -> "Setembro";
            case 10 -> "Outubro";
            case 11 -> "Novembro";
            case 12 -> "Dezembro";
            default -> "";
        };
    }

    public String lightenColor(String hexColor) {
        int r = Integer.parseInt(hexColor.substring(1, 3), 16);
        int g = Integer.parseInt(hexColor.substring(3, 5), 16);
        int b = Integer.parseInt(hexColor.substring(5, 7), 16);

        r = (int) Math.min(255, r + (255 - r) * 0.5);
        g = (int) Math.min(255, g + (255 - g) * 0.5);
        b = (int) Math.min(255, b + (255 - b) * 0.5);

        return String.format("#%02X%02X%02X", r, g, b);
    }

    public enum TipoPeso {
        K('1', "Kg"),
        T('2', "Ton"),
        N('3', "NI");

        private final char key;
        private final String value;

        TipoPeso(char key, String value) {
            this.key = key;
            this.value = value;
        }

        public char getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

        public static TipoPeso fromKey(char key) {
            for (TipoPeso tipo : values()) {
                if (tipo.key == key) {
                    return tipo;
                }
            }
            return null;
        }

        public static TipoPeso fromValue(String value) {
            for (TipoPeso tipo : values()) {
                if (tipo.value.equalsIgnoreCase(value)) {
                    return tipo;
                }
            }
            return null;
        }
    }

    String getBase64FromUrl(String url, String nomeArquivo) throws Exception {
        try (InputStream is = imageCache.getImageStream(url, nomeArquivo);
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             OutputStream encoderStream = Base64.getEncoder().wrap(out)) {
            if (is == null) {
                return "";
            }
            is.transferTo(encoderStream);
            return "data:image/png;base64," + out.toString(StandardCharsets.UTF_8);
        }
    }
}

